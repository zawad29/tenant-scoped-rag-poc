package com.example.ragpoc.document.service;

import com.example.ragpoc.audit.entity.AuditEvent;
import com.example.ragpoc.audit.service.AuditService;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.document.DocumentNotFoundException;
import com.example.ragpoc.document.DocumentSummary;
import com.example.ragpoc.document.entity.Document;
import com.example.ragpoc.document.entity.DocumentVersion;
import com.example.ragpoc.document.repository.DocumentRepository;
import com.example.ragpoc.document.repository.DocumentVersionRepository;
import com.example.ragpoc.ingestion.service.IngestionService;
import com.example.ragpoc.retrieval.service.QdrantVectorStore;
import com.example.ragpoc.storage.FileStorageService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document management: accept an upload, ingest it, and report the outcome.
 *
 * <p>Ingestion runs synchronously, so by the time an upload returns the document
 * is either active or has been rejected; there is no queue and no polled status.
 * The ingestion service is injected lazily because it pulls in the ONNX
 * pipeline, which should not be built until a document actually needs it.
 */
@Service
@Slf4j
public class DocumentService {

  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final ObjectProvider<IngestionService> ingestion;
  private final FileStorageService storage;
  private final QdrantVectorStore index;
  private final AuditService audit;

  public DocumentService(
      DocumentRepository documents,
      DocumentVersionRepository versions,
      ObjectProvider<IngestionService> ingestion,
      FileStorageService storage,
      QdrantVectorStore index,
      AuditService audit) {
    this.documents = documents;
    this.versions = versions;
    this.ingestion = ingestion;
    this.storage = storage;
    this.index = index;
    this.audit = audit;
  }

  /** Accepts and ingests a new document. */
  public UUID upload(CidContext cid, String title, String originalFilename, Path uploadedFile) {
    UUID documentId = UUID.randomUUID();
    String effectiveTitle =
        (title == null || title.isBlank()) ? titleFrom(originalFilename) : title.trim();

    FileStorageService.StoredFile stored = storage.store(cid, documentId, 1, uploadedFile);

    documents.save(
        new Document(
            documentId,
            cid.cidId(),
            effectiveTitle,
            originalFilename,
            stored.sha256(),
            cid.userId()));

    versions.save(
        new DocumentVersion(UUID.randomUUID(), documentId, 1, stored.storageKey(), stored.sha256()));
    audit.record(cid.cidId(), cid.userId(), AuditEvent.Type.DOCUMENT_UPLOADED, effectiveTitle);

    ingestion.getObject().ingest(cid, documentId, 1);

    log.info("Uploaded and ingested document {} for cid {}", documentId, cid.cidId());
    return documentId;
  }

  /**
   * Adds and ingests a new version of an existing document. Re-uploading
   * byte-identical content is a no-op rather than an expensive re-ingestion.
   *
   * @return the new version, or empty when the content was unchanged
   */
  public Optional<Integer> replace(
      CidContext cid, UUID documentId, String originalFilename, Path uploadedFile) {

    Document document = require(cid, documentId);
    int version = versions.nextVersion(documentId);

    FileStorageService.StoredFile stored = storage.store(cid, documentId, version, uploadedFile);

    if (document.active() && stored.sha256().equals(document.getContentHash())) {
      storage.delete(cid, stored.storageKey());
      log.info("Document {} re-uploaded with identical content; no new version created", documentId);
      return Optional.empty();
    }

    versions.save(
        new DocumentVersion(
            UUID.randomUUID(), documentId, version, stored.storageKey(), stored.sha256()));
    audit.record(
        cid.cidId(),
        cid.userId(),
        AuditEvent.Type.DOCUMENT_REPLACED,
        document.getTitle() + " v" + version);

    ingestion.getObject().ingest(cid, documentId, version);

    log.info("Replaced document {} with version {} for cid {}", documentId, version, cid.cidId());
    return Optional.of(version);
  }

  /**
   * Removes a document: chunks first, then files, then the row. Chunk removal is
   * verified by counting rather than assumed, so a partial deletion fails loudly.
   */
  @Transactional
  public void delete(CidContext cid, UUID documentId) {
    Document document = require(cid, documentId);

    document.markDeleting();
    documents.save(document);

    for (DocumentVersion version : versions.findByDocumentIdOrderByVersionDesc(documentId)) {
      storage.delete(cid, version.getStorageKey());
    }

    index.deleteByDocument(cid, documentId);
    long remaining = index.countByDocument(cid, documentId);
    if (remaining != 0) {
      throw new IllegalStateException(
          "Deletion incomplete for document " + documentId + ": " + remaining + " chunks remain");
    }

    // document_version rows are removed by the FK cascade.
    documents.delete(document);
    storage.deleteByDocument(cid, documentId);
    audit.record(cid.cidId(), cid.userId(), AuditEvent.Type.DOCUMENT_DELETED, document.getTitle());

    log.info("Deleted document {} for cid {}", documentId, cid.cidId());
  }

  @Transactional(readOnly = true)
  public List<DocumentSummary> list(CidContext cid) {
    List<Document> documentsForCid = documents.findByCidIdOrderByCreatedAtDesc(cid.cidId());
    List<DocumentSummary> summaries = new ArrayList<>(documentsForCid.size());
    for (Document document : documentsForCid) {
      Integer chunkCount =
          document.getCurrentVersion() == 0
              ? null
              : versions
                  .findByDocumentIdAndVersion(document.getId(), document.getCurrentVersion())
                  .map(DocumentVersion::getChunkCount)
                  .orElse(null);
      summaries.add(
          new DocumentSummary(
              document.getId(),
              document.getTitle(),
              document.getOriginalFilename(),
              document.getStatus().name(),
              document.getCurrentVersion(),
              document.getPageCount(),
              chunkCount,
              document.getUpdatedAt()));
    }
    return summaries;
  }

  /** Resolves a document id for the calling cid, or reports it as absent. */
  @Transactional(readOnly = true)
  public Document require(CidContext cid, UUID documentId) {
    return documents
        .findByIdAndCidId(documentId, cid.cidId())
        .orElseThrow(() -> new DocumentNotFoundException(documentId));
  }

  @Transactional(readOnly = true)
  public Optional<Document> findById(CidContext cid, UUID documentId) {
    return documents.findByIdAndCidId(documentId, cid.cidId());
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
