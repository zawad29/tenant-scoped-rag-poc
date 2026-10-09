package com.example.ragpoc.document;

import com.example.ragpoc.audit.AuditService;
import com.example.ragpoc.ingest.IngestionJobRepository;
import com.example.ragpoc.port.FileStoragePort;
import com.example.ragpoc.port.VectorIndexPort;
import com.example.ragpoc.tenant.TenantContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The request-side half of document management: accept an upload, queue the
 * work, and let the caller return immediately.
 *
 * <p>Uploads return as soon as the file is stored and the job is queued, so the
 * administrator sees a status rather than waiting on extraction and embedding.
 */
@Service
public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final IngestionJobRepository jobs;
  private final FileStoragePort storage;
  private final VectorIndexPort index;
  private final AuditService audit;

  public DocumentService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      IngestionJobRepository jobs,
      FileStoragePort storage,
      VectorIndexPort index,
      AuditService audit) {
    this.documents = documents;
    this.versions = versions;
    this.jobs = jobs;
    this.storage = storage;
    this.index = index;
    this.audit = audit;
  }

  /**
   * Accepts a new document.
   *
   * @param title human-readable title, used for citations and as part of the
   *     embedded text; falls back to the file name when blank
   * @return the new document's id
   */
  public UUID upload(TenantContext tenant, String title, String originalFilename, Path uploadedFile) {
    UUID documentId = UUID.randomUUID();
    String effectiveTitle = (title == null || title.isBlank()) ? titleFrom(originalFilename) : title.trim();

    FileStoragePort.StoredFile stored = storage.store(tenant, documentId, 1, uploadedFile);

    documents.insert(
        new Document(
            documentId,
            tenant.tenantId(),
            effectiveTitle,
            originalFilename,
            stored.sha256(),
            DocumentStatus.UPLOADED,
            0,
            null,
            null,
            null));

    versions.insert(
        new DocumentVersion(
            UUID.randomUUID(),
            documentId,
            tenant.tenantId(),
            1,
            stored.storageKey(),
            stored.sha256(),
            null,
            null,
            0,
            null));

    jobs.enqueue(tenant, documentId, 1);
    audit.record(tenant, AuditService.Type.DOCUMENT_UPLOADED, effectiveTitle);

    log.info("Queued ingestion of document {} for tenant {}", documentId, tenant.tenantId());
    return documentId;
  }

  /**
   * Adds a new version of an existing document.
   *
   * <p>Re-uploading byte-identical content is a no-op rather than an
   * expensive re-ingestion: the hash is compared against the active version and
   * the just-stored copy is discarded. Anything else would mean an accidental
   * double upload burns a full embedding pass and produces a version identical
   * to the one it replaces.
   *
   * @return the new version, or empty when the content was unchanged
   */
  public Optional<Integer> replace(
      TenantContext tenant, UUID documentId, String originalFilename, Path uploadedFile) {

    Document document = require(tenant, documentId);
    int version = versions.nextVersion(tenant, documentId);

    FileStoragePort.StoredFile stored = storage.store(tenant, documentId, version, uploadedFile);

    if (document.active() && stored.sha256().equals(document.contentHash())) {
      storage.delete(tenant, stored.storageKey());
      log.info(
          "Document {} re-uploaded with identical content; no new version created", documentId);
      return Optional.empty();
    }

    versions.insert(
        new DocumentVersion(
            UUID.randomUUID(),
            documentId,
            tenant.tenantId(),
            version,
            stored.storageKey(),
            stored.sha256(),
            null,
            null,
            0,
            null));

    jobs.enqueue(tenant, documentId, version);
    audit.record(tenant, AuditService.Type.DOCUMENT_REPLACED, document.title() + " v" + version);

    log.info("Queued version {} of document {} for tenant {}", version, documentId, tenant.tenantId());
    return Optional.of(version);
  }

  /**
   * Removes a document: chunks first, then files, then the row.
   *
   * <p>Chunk removal is verified by counting rather than assumed. A deletion
   * that silently leaves chunks behind would mean deleted content is still
   * answerable — a data-retention failure that a 200 response would hide.
   */
  public void delete(TenantContext tenant, UUID documentId) {
    Document document = require(tenant, documentId);

    documents.markDeleting(tenant, documentId);

    for (DocumentVersion version : versions.listByDocument(tenant, documentId)) {
      storage.delete(tenant, version.storageKey());
    }

    index.deleteByDocument(tenant, documentId);

    int remaining = index.countByDocument(tenant, documentId);
    if (remaining != 0) {
      // Leaves the document in DELETING, which is visible on the admin screen,
      // instead of reporting success for a partial deletion.
      throw new IllegalStateException(
          "Deletion incomplete for document "
              + documentId
              + ": "
              + remaining
              + " chunks are still indexed");
    }

    documents.deleteByIdAndTenant(tenant, documentId);
    storage.deleteByDocument(tenant, documentId);
    audit.record(tenant, AuditService.Type.DOCUMENT_DELETED, document.title());

    log.info("Deleted document {} for tenant {}", documentId, tenant.tenantId());
  }

  public List<DocumentRepository.DocumentSummary> list(TenantContext tenant) {
    return documents.listSummaries(tenant);
  }

  /** Resolves a document id for the calling tenant, or reports it as absent. */
  public Document require(TenantContext tenant, UUID documentId) {
    return documents
        .findByIdAndTenant(tenant, documentId)
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
  }

  /** Tenant-scoped lookup that returns empty instead of throwing. */
  public java.util.Optional<Document> findById(TenantContext tenant, UUID documentId) {
    return documents.findByIdAndTenant(tenant, documentId);
  }

  private static String titleFrom(String originalFilename) {
    if (originalFilename == null || originalFilename.isBlank()) {
      return "Untitled document";
    }
    int dot = originalFilename.lastIndexOf('.');
    String base = dot > 0 ? originalFilename.substring(0, dot) : originalFilename;
    return base.isBlank() ? "Untitled document" : base;
  }
}
