package com.example.ragpoc.ingestion.service;

import com.example.ragpoc.audit.entity.AuditEvent;
import com.example.ragpoc.audit.service.AuditService;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.config.RagProperties;
import com.example.ragpoc.document.entity.Document;
import com.example.ragpoc.document.entity.DocumentVersion;
import com.example.ragpoc.document.repository.DocumentRepository;
import com.example.ragpoc.document.repository.DocumentVersionRepository;
import com.example.ragpoc.ingestion.IngestionValidationException;
import com.example.ragpoc.ingestion.chunk.Chunk;
import com.example.ragpoc.ingestion.chunk.ChunkIds;
import com.example.ragpoc.ingestion.chunk.CleanedPage;
import com.example.ragpoc.ingestion.chunk.DocumentChunker;
import com.example.ragpoc.ingestion.chunk.RawPage;
import com.example.ragpoc.ingestion.chunk.TextCleaner;
import com.example.ragpoc.ingestion.tika.TikaTextExtractor;
import com.example.ragpoc.retrieval.search.ChunkMetadata;
import com.example.ragpoc.retrieval.search.DocumentIndex;
import com.example.ragpoc.storage.FileStorageService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a stored file into searchable chunks, synchronously.
 *
 * <p>Extraction, cleaning and chunking touch no database rows; the writes are
 * done in one short transaction at the end. Because this runs on the upload
 * request thread, a rejection (a scanned file, say) surfaces to the caller
 * immediately rather than through a polled status.
 *
 * <p>Chunks are written as Spring AI {@link Document}s, so the vector store
 * embeds them through the configured {@code EmbeddingModel}. That means a
 * hosted embedding service needs no change here.
 */
@Service
@Lazy
@Slf4j
public class IngestionService {

  private final TikaTextExtractor extractor;
  private final TextCleaner cleaner;
  private final DocumentChunker chunker;
  private final DocumentIndex index;
  private final FileStorageService storage;
  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final AuditService audit;
  private final TransactionTemplate transactions;
  private final String embeddingModelId;
  private final int embeddingDimension;

  public IngestionService(
      TikaTextExtractor extractor,
      TextCleaner cleaner,
      DocumentChunker chunker,
      DocumentIndex index,
      FileStorageService storage,
      DocumentRepository documents,
      DocumentVersionRepository versions,
      AuditService audit,
      TransactionTemplate transactions,
      RagProperties properties) {
    this.extractor = extractor;
    this.cleaner = cleaner;
    this.chunker = chunker;
    this.index = index;
    this.storage = storage;
    this.documents = documents;
    this.versions = versions;
    this.audit = audit;
    this.transactions = transactions;
    this.embeddingModelId = properties.embedding().modelId();
    this.embeddingDimension = properties.embedding().dimension();
  }

  /**
   * Ingests a stored version and makes it active.
   *
   * @throws IngestionValidationException when the document is rejected; the
   *     staged version is cleaned up and the document marked failed
   */
  public void ingest(CidContext cid, UUID documentId, int version) {
    Document document =
        documents
            .findByIdAndCidId(documentId, cid.cidId())
            .orElseThrow(() -> new IllegalStateException("Document no longer exists: " + documentId));
    DocumentVersion documentVersion =
        versions
            .findByDocumentIdAndVersion(documentId, version)
            .orElseThrow(() -> new IllegalStateException("No document_version row for version " + version));

    document.markProcessing();
    documents.save(document);
    log.info(
        "ingest start cid={} document={} version={} title=\"{}\" storageKey={}",
        cid.cidId(),
        documentId,
        version,
        document.getTitle(),
        documentVersion.getStorageKey());

    try {
      int chunkCount = run(cid, document, documentVersion);
      log.info(
          "ingest done cid={} document={} version={} chunks={} status=ACTIVE",
          cid.cidId(), documentId, version, chunkCount);
    } catch (RuntimeException e) {
      cleanUpAfterFailure(cid, document, documentVersion, e);
      throw e;
    }
  }

  private int run(CidContext cid, Document document, DocumentVersion version) {
    Path source = storage.pathOf(cid, version.getStorageKey());
    log.debug("ingest[{}] v{} resolved stored file {}", document.getId(), version.getVersion(), source);

    List<RawPage> pages = extractor.extract(source);
    log.info(
        "ingest step=extract cid={} document={} version={} pages={}",
        cid.cidId(), document.getId(), version.getVersion(), pages.size());

    List<CleanedPage> cleaned = cleaner.clean(pages);
    long blocks = cleaned.stream().mapToLong(page -> page.blocks().size()).sum();
    log.info(
        "ingest step=clean cid={} document={} version={} blocks={}",
        cid.cidId(), document.getId(), version.getVersion(), blocks);

    List<Chunk> chunks = chunker.chunk(document.getTitle(), cleaned);
    log.info(
        "ingest step=chunk cid={} document={} version={} chunks={}",
        cid.cidId(), document.getId(), version.getVersion(), chunks.size());

    if (chunks.isEmpty()) {
      throw new IngestionValidationException(
          IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT);
    }

    List<org.springframework.ai.document.Document> records = new ArrayList<>(chunks.size());
    for (Chunk chunk : chunks) {
      records.add(toIndexedDocument(cid, document, version.getVersion(), chunk));
      log.debug(
          "ingest[{}] v{} chunk={} pages={} section={}",
          document.getId(), version.getVersion(), chunk.index(), chunk.pageRange(), chunk.sectionTitle());
    }

    // The store embeds and upserts. Each version's chunk ids are distinct, so a
    // replacement never collides with the version it supersedes.
    index.add(records);
    log.info(
        "ingest step=index cid={} document={} version={} documents={} embeddingModel={} dimension={}",
        cid.cidId(),
        document.getId(),
        version.getVersion(),
        records.size(),
        embeddingModelId,
        embeddingDimension);

    transactions.executeWithoutResult(
        status -> {
          version.recordIngestionResult(embeddingModelId, embeddingDimension, records.size());
          versions.save(version);
          document.activate(version.getVersion(), version.getContentHash(), pages.size());
          documents.save(document);
        });
    log.info(
        "ingest step=activate cid={} document={} version={} status=ACTIVE",
        cid.cidId(), document.getId(), version.getVersion());

    // Only after the switch: remove superseded versions.
    cleanUpSupersededVersions(cid, document.getId(), version.getVersion());

    return records.size();
  }

  private static org.springframework.ai.document.Document toIndexedDocument(
      CidContext cid, Document document, int version, Chunk chunk) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(ChunkMetadata.CID_ID, cid.cidId().toString());
    metadata.put(ChunkMetadata.DOCUMENT_ID, document.getId().toString());
    metadata.put(ChunkMetadata.VERSION, version);
    metadata.put(ChunkMetadata.CHUNK_INDEX, chunk.index());
    if (chunk.sectionTitle() != null && !chunk.sectionTitle().isBlank()) {
      metadata.put(ChunkMetadata.SECTION_TITLE, chunk.sectionTitle());
    }
    metadata.put(ChunkMetadata.DOC_TITLE, document.getTitle());

    return org.springframework.ai.document.Document.builder()
        .id(ChunkIds.forChunk(cid.cidId(), document.getId(), version, chunk.index()).toString())
        .text(chunk.text())
        .metadata(metadata)
        .build();
  }

  private void cleanUpSupersededVersions(CidContext cid, UUID documentId, int keepVersion) {
    for (DocumentVersion superseded :
        versions.findByDocumentIdAndVersionNot(documentId, keepVersion)) {
      log.info(
          "ingest step=cleanup cid={} document={} removing superseded version={}",
          cid.cidId(), documentId, superseded.getVersion());
      index.deleteByDocumentVersion(cid, documentId, superseded.getVersion());
      storage.delete(cid, superseded.getStorageKey());
      versions.delete(superseded);
    }
  }

  /** Best-effort removal of anything a failed attempt produced. */
  private void cleanUpAfterFailure(
      CidContext cid, Document document, DocumentVersion version, RuntimeException cause) {
    log.info(
        "ingest step=cleanup-after-failure cid={} document={} version={}",
        cid.cidId(), document.getId(), version.getVersion());
    try {
      index.deleteByDocumentVersion(cid, document.getId(), version.getVersion());
    } catch (RuntimeException e) {
      log.warn("Could not remove chunks of failed version: {}", e.getMessage());
    }
    try {
      storage.delete(cid, version.getStorageKey());
    } catch (RuntimeException e) {
      log.warn("Could not remove file of failed version: {}", e.getMessage());
    }
    try {
      versions.delete(version);
    } catch (RuntimeException e) {
      log.warn("Could not remove failed version row: {}", e.getMessage());
    }
    if (document.getCurrentVersion() == 0) {
      document.markFailed();
      documents.save(document);
    }
    audit.record(cid.cidId(), cid.userId(), AuditEvent.Type.INGESTION_FAILED, describe(cause));
    log.warn(
        "Ingestion failed for document {} version {}: {}",
        document.getId(),
        version.getVersion(),
        describe(cause));
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return message == null ? e.getClass().getSimpleName() : message;
  }
}
