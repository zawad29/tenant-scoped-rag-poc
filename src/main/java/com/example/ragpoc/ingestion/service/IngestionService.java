package com.example.ragpoc.ingestion.service;

import com.example.ragpoc.audit.entity.AuditEvent;
import com.example.ragpoc.audit.service.AuditService;
import com.example.ragpoc.cid.CidContext;
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
import com.example.ragpoc.ingestion.pdf.PdfPageExtractor;
import com.example.ragpoc.retrieval.model.ChunkRecord;
import com.example.ragpoc.retrieval.service.EmbeddingService;
import com.example.ragpoc.retrieval.service.QdrantVectorStore;
import com.example.ragpoc.storage.FileStorageService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a stored PDF into searchable chunks, synchronously.
 *
 * <p>Extraction, cleaning, chunking and embedding touch no database rows; the
 * writes are done in one short transaction at the end. Because this runs on the
 * upload request thread, a rejection (a scanned PDF, say) surfaces to the caller
 * immediately rather than through a polled status.
 *
 * <p>{@code @Lazy}: it pulls in the ONNX pipeline, so it is created the first
 * time a document is ingested rather than at context startup.
 */
@Service
@Lazy
@Slf4j
public class IngestionService {

  private final PdfPageExtractor extractor;
  private final TextCleaner cleaner;
  private final DocumentChunker chunker;
  private final EmbeddingService embedding;
  private final QdrantVectorStore index;
  private final FileStorageService storage;
  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final AuditService audit;
  private final TransactionTemplate transactions;

  public IngestionService(
      PdfPageExtractor extractor,
      TextCleaner cleaner,
      DocumentChunker chunker,
      EmbeddingService embedding,
      QdrantVectorStore index,
      FileStorageService storage,
      DocumentRepository documents,
      DocumentVersionRepository versions,
      AuditService audit,
      TransactionTemplate transactions) {
    this.extractor = extractor;
    this.cleaner = cleaner;
    this.chunker = chunker;
    this.embedding = embedding;
    this.index = index;
    this.storage = storage;
    this.documents = documents;
    this.versions = versions;
    this.audit = audit;
    this.transactions = transactions;
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

    try {
      int chunkCount = run(cid, document, documentVersion);
      log.info(
          "Ingested document {} version {} for cid {}: {} chunks",
          documentId,
          version,
          cid.cidId(),
          chunkCount);
    } catch (RuntimeException e) {
      cleanUpAfterFailure(cid, document, documentVersion, e);
      throw e;
    }
  }

  private int run(CidContext cid, Document document, DocumentVersion version) {
    Path source = storage.pathOf(cid, version.getStorageKey());

    List<RawPage> pages = extractor.extract(source);
    List<CleanedPage> cleaned = cleaner.clean(pages);
    List<Chunk> chunks = chunker.chunk(document.getTitle(), cleaned);

    if (chunks.isEmpty()) {
      throw new IngestionValidationException(
          IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT);
    }

    // Embed the contextualised form; the stored text stays clean.
    List<String> textsToEmbed = new ArrayList<>(chunks.size());
    for (Chunk chunk : chunks) {
      textsToEmbed.add(contextualise(document.getTitle(), chunk));
    }
    List<float[]> vectors = embedding.embedAll(textsToEmbed);

    if (vectors.size() != chunks.size()) {
      throw new IllegalStateException(
          "Embedding returned " + vectors.size() + " vectors for " + chunks.size() + " chunks");
    }

    List<ChunkRecord> records = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      records.add(
          new ChunkRecord(
              ChunkIds.forChunk(cid.cidId(), document.getId(), version.getVersion(), chunk.index()),
              document.getId(),
              version.getVersion(),
              chunk.index(),
              chunk.pageStart(),
              chunk.pageEnd(),
              chunk.sectionTitle(),
              document.getTitle(),
              chunk.text(),
              embedding.modelId(),
              vectors.get(i)));
    }

    // Qdrant writes first, as inactive points, then promote. Preferring a brief
    // both-active window over a gap means a replacement never makes the document
    // unanswerable.
    index.upsert(cid, records);
    index.setActive(cid, document.getId(), version.getVersion(), true);

    transactions.executeWithoutResult(
        status -> {
          version.recordIngestionResult(embedding.modelId(), embedding.dimension(), records.size());
          versions.save(version);
          document.activate(version.getVersion(), version.getContentHash(), pages.size());
          documents.save(document);
        });

    // Only after the switch: remove superseded versions.
    cleanUpSupersededVersions(cid, document.getId(), version.getVersion());

    return records.size();
  }

  private void cleanUpSupersededVersions(CidContext cid, UUID documentId, int keepVersion) {
    for (DocumentVersion superseded :
        versions.findByDocumentIdAndVersionNot(documentId, keepVersion)) {
      index.deleteByDocumentVersion(cid, documentId, superseded.getVersion());
      storage.delete(cid, superseded.getStorageKey());
      versions.delete(superseded);
    }
  }

  /** Best-effort removal of anything a failed attempt produced. */
  private void cleanUpAfterFailure(
      CidContext cid, Document document, DocumentVersion version, RuntimeException cause) {
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
    audit.record(
        cid.cidId(), cid.userId(), AuditEvent.Type.INGESTION_FAILED, describe(cause));
    log.warn(
        "Ingestion failed for document {} version {}: {}",
        document.getId(),
        version.getVersion(),
        describe(cause));
  }

  /** The text that actually gets embedded, but never stored or quoted. */
  static String contextualise(String documentTitle, Chunk chunk) {
    StringBuilder text = new StringBuilder(documentTitle);
    if (chunk.sectionTitle() != null && !chunk.sectionTitle().isBlank()) {
      text.append(". ").append(chunk.sectionTitle());
    }
    return text.append(". ").append(chunk.text()).toString();
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return message == null ? e.getClass().getSimpleName() : message;
  }
}
