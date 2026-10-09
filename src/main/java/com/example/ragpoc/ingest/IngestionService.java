package com.example.ragpoc.ingest;

import com.example.ragpoc.audit.AuditService;
import com.example.ragpoc.document.Document;
import com.example.ragpoc.document.DocumentRepository;
import com.example.ragpoc.document.DocumentStatus;
import com.example.ragpoc.document.DocumentVersion;
import com.example.ragpoc.document.DocumentVersionRepository;
import com.example.ragpoc.port.ChunkRecord;
import com.example.ragpoc.port.EmbeddingPort;
import com.example.ragpoc.port.FileStoragePort;
import com.example.ragpoc.port.VectorIndexPort;
import com.example.ragpoc.tenant.TenantContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a stored PDF into searchable chunks.
 *
 * <p>Runs on a worker thread with a {@link TenantContext} built from the job row.
 * Every call below takes that context as an argument, so nothing depends on
 * ambient state and no part of this class can accidentally act for the wrong
 * tenant.
 *
 * <p>The work is split into two phases. Extraction, cleaning, chunking and
 * embedding are slow and touch no database rows; the writes are then done in one
 * short transaction. Holding a transaction open across 30 ms-per-chunk embedding
 * would tie up a connection for the whole document, and in a replacement it
 * would hold locks that block readers for no benefit.
 */
@Service
public class IngestionService {

  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

  private final PdfPageExtractor extractor;
  private final TextCleaner cleaner;
  private final DocumentChunker chunker;
  private final EmbeddingPort embeddingPort;
  private final VectorIndexPort index;
  private final FileStoragePort storage;
  private final DocumentRepository documents;
  private final DocumentVersionRepository versions;
  private final IngestionJobRepository jobs;
  private final AuditService audit;
  private final TransactionTemplate transactions;
  private final int maxAttempts;

  public IngestionService(
      PdfPageExtractor extractor,
      TextCleaner cleaner,
      DocumentChunker chunker,
      EmbeddingPort embeddingPort,
      VectorIndexPort index,
      FileStoragePort storage,
      DocumentRepository documents,
      DocumentVersionRepository versions,
      IngestionJobRepository jobs,
      AuditService audit,
      TransactionTemplate transactions,
      com.example.ragpoc.config.RagProperties properties) {
    this.extractor = extractor;
    this.cleaner = cleaner;
    this.chunker = chunker;
    this.embeddingPort = embeddingPort;
    this.index = index;
    this.storage = storage;
    this.documents = documents;
    this.versions = versions;
    this.jobs = jobs;
    this.audit = audit;
    this.transactions = transactions;
    this.maxAttempts = properties.ingestion().maxAttempts();
  }

  /**
   * Processes one claimed job.
   *
   * <p>Failure handling distinguishes the two kinds of problem. A rejection —
   * scanned PDF, wrong format, password-protected — is final: the input will not
   * change, so retrying wastes attempts and leaves the document showing as
   * in-progress forever. Anything else is treated as transient and retried up to
   * the configured budget, then marked failed with the error recorded.
   */
  public void process(IngestionJobRepository.ClaimedJob job) {
    TenantContext tenant = TenantContext.system(job.tenantId());

    try {
      Document document =
          documents
              .findByIdAndTenant(tenant, job.documentId())
              .orElseThrow(
                  () ->
                      new IngestionValidationException(
                          IngestionValidationException.Reason.MALFORMED,
                          new IllegalStateException("Document no longer exists")));
      DocumentVersion version =
          versions
              .find(tenant, job.documentId(), job.version())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "No document_version row for version " + job.version()));

      documents.markProcessing(tenant, job.documentId());

      int chunkCount = ingest(tenant, document, version);
      jobs.markSucceeded(job.jobId());

      log.info(
          "Ingested document {} version {} for tenant {}: {} chunks",
          job.documentId(),
          job.version(),
          job.tenantId(),
          chunkCount);

    } catch (IngestionValidationException e) {
      jobs.markFailed(job.jobId(), e.getMessage(), false, maxAttempts);
      documents.markFailed(tenant, job.documentId());
      audit.record(tenant, AuditService.Type.INGESTION_FAILED, e.getMessage());
      log.warn(
          "Rejected document {} for tenant {}: {}",
          job.documentId(),
          job.tenantId(),
          e.getMessage());

    } catch (RuntimeException e) {
      boolean retryable = job.attempts() < maxAttempts;
      jobs.markFailed(job.jobId(), describe(e), retryable, maxAttempts);
      if (!retryable) {
        documents.markFailed(tenant, job.documentId());
      }
      log.error(
          "Ingestion failed for document {} (attempt {}{}): {}",
          job.documentId(),
          job.attempts(),
          retryable ? ", will retry" : ", giving up",
          e.toString());
    }
  }

  /**
   * The pipeline: extract, clean, chunk, embed, index, activate.
   *
   * @return the number of chunks indexed
   */
  private int ingest(TenantContext tenant, Document document, DocumentVersion version) {
    Path source = storage.pathOf(tenant, version.storageKey());

    List<RawPage> pages = extractor.extract(source);
    List<CleanedPage> cleaned = cleaner.clean(pages);
    List<Chunk> chunks = chunker.chunk(document.title(), cleaned);

    if (chunks.isEmpty()) {
      throw new IngestionValidationException(
          IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT);
    }

    // Embed the contextualised form: document and section titles are prepended
    // because a chunk lifted out of its document is often ambiguous on its own,
    // which hurts retrieval for exactly the chunks that need it most. The stored
    // text stays clean, so citations quote the document rather than a
    // synthesised string.
    List<String> textsToEmbed = new ArrayList<>(chunks.size());
    for (Chunk chunk : chunks) {
      textsToEmbed.add(contextualise(document.title(), chunk));
    }
    List<float[]> vectors = embeddingPort.embedAll(textsToEmbed);

    if (vectors.size() != chunks.size()) {
      throw new IllegalStateException(
          "Embedding returned " + vectors.size() + " vectors for " + chunks.size() + " chunks");
    }

    List<ChunkRecord> records = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      records.add(
          new ChunkRecord(
              ChunkIds.forChunk(tenant.tenantId(), document.id(), version.version(), chunk.index()),
              document.id(),
              version.version(),
              chunk.index(),
              chunk.pageStart(),
              chunk.pageEnd(),
              chunk.sectionTitle(),
              document.title(),
              chunk.text(),
              embeddingPort.modelId(),
              vectors.get(i)));
    }

    // One short transaction for all the writes: either the new version is
    // complete and active, or nothing changed and the old version still answers.
    transactions.executeWithoutResult(
        status -> {
          index.upsert(tenant, records);
          versions.recordIngestionResult(
              tenant,
              document.id(),
              version.version(),
              embeddingPort.modelId(),
              embeddingPort.dimension(),
              records.size());
          documents.activate(
              tenant,
              document.id(),
              version.version(),
              version.contentHash(),
              pages.size());
        });

    // Only after the switch: remove superseded versions' chunks. Doing this
    // earlier would leave the document temporarily unanswerable.
    cleanUpSupersededVersions(tenant, document.id(), version.version());

    return records.size();
  }

  /** Removes chunks, version rows and files that the new version replaced. */
  private void cleanUpSupersededVersions(TenantContext tenant, UUID documentId, int keepVersion) {
    for (int superseded : versions.versionsOtherThan(tenant, documentId, keepVersion)) {
      int removed = index.deleteByDocumentVersion(tenant, documentId, superseded);
      versions
          .find(tenant, documentId, superseded)
          .ifPresent(
              old -> {
                storage.delete(tenant, old.storageKey());
                versions.delete(tenant, documentId, superseded);
              });
      log.debug(
          "Removed {} chunks of superseded version {} of document {}", removed, superseded, documentId);
    }
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

  /** Marks a document as failed while leaving an active version in place. */
  public void markDocumentFailed(TenantContext tenant, UUID documentId) {
    documents.markFailed(tenant, documentId);
  }

  /** Convenience for tests and tooling: the status a document is currently in. */
  public DocumentStatus statusOf(TenantContext tenant, UUID documentId) {
    return documents
        .findByIdAndTenant(tenant, documentId)
        .map(Document::status)
        .orElseThrow(() -> new IllegalArgumentException("No such document"));
  }
}
