package com.example.ragpoc.ingest;

import static com.example.ragpoc.support.PdfFixtures.writeEmptyTextPdf;
import static com.example.ragpoc.support.PdfFixtures.writePdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragpoc.document.Document;
import com.example.ragpoc.document.DocumentNotFoundException;
import com.example.ragpoc.document.DocumentRepository;
import com.example.ragpoc.document.DocumentService;
import com.example.ragpoc.document.DocumentStatus;
import com.example.ragpoc.port.VectorIndexPort;
import com.example.ragpoc.support.PostgresTestSupport;
import com.example.ragpoc.tenant.Role;
import com.example.ragpoc.tenant.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The ingestion pipeline end to end: upload, process, replace, delete.
 *
 * <p>The background worker is switched off and jobs are claimed and processed
 * explicitly. That makes these tests deterministic — no polling, no sleeps, no
 * flaky waits for a status to change — while still exercising the real claim
 * query. The polling path itself is covered by the admin screen test.
 */
@SpringBootTest
class IngestionPipelineIT extends PostgresTestSupport {

  private static Path storageRoot;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerDatasourceProperties(registry);
    registry.add("rag.ingestion.worker-enabled", () -> "false");
    registry.add(
        "rag.storage.local-root",
        () -> {
          try {
            if (storageRoot == null) {
              storageRoot = Files.createTempDirectory("rag-storage-it");
            }
            return storageRoot.toString();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Autowired private DocumentService documents;
  @Autowired private IngestionService ingestion;
  @Autowired private IngestionJobRepository jobs;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorIndexPort index;
  @Autowired private DataSource dataSource;

  private JdbcClient jdbc;
  private TenantContext tenant;
  private TenantContext otherTenant;

  @TempDir Path tempDir;

  @BeforeAll
  static void reportStorage() {
    // Storage is shared across the class; individual tests clean the database.
  }

  @BeforeEach
  void setUp() {
    resetDatabase(dataSource);
    jdbc = JdbcClient.create(dataSource);
    tenant = TenantContext.of(seedTenant(dataSource, "Northwind"), null, Role.TENANT_ADMIN);
    otherTenant = TenantContext.of(seedTenant(dataSource, "Contoso"), null, Role.TENANT_ADMIN);
  }

  // --- happy path -----------------------------------------------------------

  @Test
  @DisplayName("an uploaded PDF becomes ACTIVE with searchable chunks")
  void uploadBecomesSearchable() throws IOException {
    String canary = "Project Zephyr Quokka Protocol";
    Path pdf =
        writePdf(
            tempDir,
            "policy.pdf",
            List.of(
                "Introduction\nThis document describes the " + canary + " in detail.",
                "Scope\nIt applies to every permanent employee."));

    UUID documentId = documents.upload(tenant, "Zephyr Policy", "policy.pdf", pdf);
    runPendingJobs();

    Document document = documentRepository.findByIdAndTenant(tenant, documentId).orElseThrow();
    assertThat(document.status()).isEqualTo(DocumentStatus.ACTIVE);
    assertThat(document.currentVersion()).isEqualTo(1);
    assertThat(document.pageCount()).isEqualTo(2);

    assertThat(index.countByDocument(tenant, documentId)).isPositive();

    // The canary must be findable, or a "not found" result would prove nothing.
    List<?> keywordHits =
        index.keywordSearch(tenant, "Zephyr Quokka", 10, VectorIndexPort.SearchFilter.none());
    assertThat(keywordHits).as("the canary text must be retrievable").isNotEmpty();
  }

  @Test
  @DisplayName("chunks record the pages and section titles they came from")
  void chunksCarryPagesAndSections() throws IOException {
    Path pdf =
        writePdf(
            tempDir,
            "sections.pdf",
            List.of("ANNUAL LEAVE\nEmployees accrue eighteen days per year."));

    UUID documentId = documents.upload(tenant, "Handbook", "sections.pdf", pdf);
    runPendingJobs();

    var rows =
        jdbc.sql("SELECT page_start, page_end, section_title FROM chunk WHERE document_id = :id")
            .param("id", documentId)
            .query(
                (rs, n) ->
                    new Object[] {
                      rs.getInt("page_start"), rs.getInt("page_end"), rs.getString("section_title")
                    })
            .list();

    assertThat(rows).isNotEmpty();
    assertThat(rows).allMatch(row -> ((Integer) row[0]) == 1 && ((Integer) row[1]) == 1);
    assertThat(rows).anyMatch(row -> "ANNUAL LEAVE".equals(row[2]));
  }

  @Test
  @DisplayName("chunk ids are deterministic, so a reprocessed document does not duplicate rows")
  void chunkIdsAreDeterministic() throws IOException {
    Path pdf = writePdf(tempDir, "det.pdf", List.of("Some content for determinism checks."));

    UUID documentId = documents.upload(tenant, "Det", "det.pdf", pdf);
    runPendingJobs();
    List<UUID> firstIds = chunkIdsOf(documentId);

    // Simulate a retry of the same version: force the job back into the queue.
    jdbc.sql("UPDATE ingestion_job SET state = 'QUEUED' WHERE document_id = :id")
        .param("id", documentId)
        .update();
    runPendingJobs();

    assertThat(chunkIdsOf(documentId)).containsExactlyElementsOf(firstIds);
  }

  // --- replacement ----------------------------------------------------------

  @Test
  @DisplayName("a replacement switches atomically: new text answers, old text does not")
  void replacementSupersedesTheOldVersion() throws IOException {
    Path first = writePdf(tempDir, "v1.pdf", List.of("The old policy mentions Falcon Zulu."));
    UUID documentId = documents.upload(tenant, "Policy", "v1.pdf", first);
    runPendingJobs();

    Path second =
        writePdf(tempDir, "v2.pdf", List.of("The new policy mentions Osprey Yankee instead."));
    documents.replace(tenant, documentId, "v2.pdf", second);
    runPendingJobs();

    Document document = documentRepository.findByIdAndTenant(tenant, documentId).orElseThrow();
    assertThat(document.currentVersion()).isEqualTo(2);

    assertThat(index.keywordSearch(tenant, "Osprey Yankee", 10, VectorIndexPort.SearchFilter.none()))
        .as("the new version must be answerable")
        .isNotEmpty();
    assertThat(index.keywordSearch(tenant, "Falcon Zulu", 10, VectorIndexPort.SearchFilter.none()))
        .as("the superseded version must no longer be answerable")
        .isEmpty();

    // Superseded chunks are cleaned up, not merely filtered out.
    assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE document_id = :id AND version = 1")
            .param("id", documentId)
            .query(Integer.class)
            .single())
        .isZero();
  }

  @Test
  @DisplayName("an ACTIVE document keeps answering while a replacement is being ingested")
  void activeDocumentStaysAnswerableDuringReplacement() throws IOException {
    // The failure this guards against: setting status to PROCESSING during a
    // replacement removes the document from search entirely, so updating a
    // document causes an outage for that document.
    Path first = writePdf(tempDir, "v1.pdf", List.of("The old policy mentions Falcon Zulu."));
    UUID documentId = documents.upload(tenant, "Policy", "v1.pdf", first);
    runPendingJobs();

    Path second = writePdf(tempDir, "v2.pdf", List.of("Replacement content about Osprey."));
    documents.replace(tenant, documentId, "v2.pdf", second);

    // Deliberately do NOT process the job yet: this is the in-between state.
    Document duringReplacement =
        documentRepository.findByIdAndTenant(tenant, documentId).orElseThrow();

    assertThat(duringReplacement.status()).isEqualTo(DocumentStatus.ACTIVE);
    assertThat(index.keywordSearch(tenant, "Falcon Zulu", 10, VectorIndexPort.SearchFilter.none()))
        .as("the existing version must remain answerable during the update")
        .isNotEmpty();
  }

  @Test
  @DisplayName("re-uploading identical content creates no new version")
  void identicalReuploadIsIgnored() throws IOException {
    Path pdf = writePdf(tempDir, "same.pdf", List.of("Unchanged content that will be re-uploaded."));
    UUID documentId = documents.upload(tenant, "Same", "same.pdf", pdf);
    runPendingJobs();

    // The same bytes, not the same content: PDFBox stamps every generated file
    // with a creation timestamp and a document id, so regenerating the "same"
    // PDF produces a different hash. Deduplication compares file bytes, so the
    // test has to upload the actual file again.
    //
    // This is also a real limitation worth knowing: re-exporting a document from
    // Word produces new bytes even when the text is unchanged, and that is
    // treated as a genuine new version. Hashing extracted text instead would fix
    // that but would also silently discard a document whose only change was in
    // its formatting, so byte identity is the more predictable rule.
    Path identical = tempDir.resolve("same-again.pdf");
    Files.copy(pdf, identical);

    var newVersion = documents.replace(tenant, documentId, "same-again.pdf", identical);

    assertThat(newVersion).as("byte-identical content must not create a version").isEmpty();
    assertThat(versionCount(documentId)).isEqualTo(1);
    assertThat(documentRepository.findByIdAndTenant(tenant, documentId).orElseThrow().currentVersion())
        .isEqualTo(1);
  }

  // --- failure and deletion -------------------------------------------------

  @Test
  @DisplayName("a PDF with no text layer fails the document with an actionable reason")
  void scannedDocumentIsRejected() throws IOException {
    Path scanLike = writeEmptyTextPdf(tempDir, "scan.pdf", 3);

    UUID documentId = documents.upload(tenant, "Scan", "scan.pdf", scanLike);
    runPendingJobs();

    Document document = documentRepository.findByIdAndTenant(tenant, documentId).orElseThrow();
    assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);

    String jobState =
        jdbc.sql("SELECT state FROM ingestion_job WHERE document_id = :id")
            .param("id", documentId)
            .query(String.class)
            .single();
    assertThat(jobState).isEqualTo("FAILED");

    String error =
        jdbc.sql("SELECT error FROM ingestion_job WHERE document_id = :id")
            .param("id", documentId)
            .query(String.class)
            .single();
    assertThat(error).containsIgnoringCase("scanned");

    // A rejection is final, so it must not be retried.
    assertThat(jdbc.sql("SELECT attempts FROM ingestion_job WHERE document_id = :id")
            .param("id", documentId)
            .query(Integer.class)
            .single())
        .isEqualTo(1);

    assertThat(index.countByDocument(tenant, documentId)).isZero();

    // And it must be recorded as an audit event.
    assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE type = 'INGESTION_FAILED'")
            .query(Integer.class)
            .single())
        .isPositive();
  }

  @Test
  @DisplayName("deleting a document removes its chunks, versions and files")
  void deleteRemovesEverything() throws IOException {
    Path pdf = writePdf(tempDir, "delete-me.pdf", List.of("Content that must become unreachable."));
    UUID documentId = documents.upload(tenant, "Delete Me", "delete-me.pdf", pdf);
    runPendingJobs();
    String storageKey = storageKeyOf(documentId, 1);

    documents.delete(tenant, documentId);

    assertThat(index.countByDocument(tenant, documentId)).isZero();
    assertThat(documentRepository.findByIdAndTenant(tenant, documentId)).isEmpty();
    assertThat(jdbc.sql("SELECT count(*) FROM document_version WHERE document_id = :id")
            .param("id", documentId)
            .query(Integer.class)
            .single())
        .isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM chunk WHERE document_id = :id")
            .param("id", documentId)
            .query(Integer.class)
            .single())
        .isZero();

    // The file must be gone too: a deleted document must not linger on disk.
    assertThat(Files.exists(Path.of(storageRoot.toString(), storageKey))).isFalse();

    assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE type = 'DOCUMENT_DELETED'")
            .query(Integer.class)
            .single())
        .isPositive();
  }

  @Test
  @DisplayName("a document id from another tenant is reported as not found")
  void anotherTenantsDocumentIsNotFound() throws IOException {
    Path pdf = writePdf(tempDir, "mine.pdf", List.of("Belongs to the first tenant."));
    UUID documentId = documents.upload(tenant, "Mine", "mine.pdf", pdf);

    // The IDOR case: tenant B holds tenant A's document id.
    assertThatThrownBy(() -> documents.require(otherTenant, documentId))
        .isInstanceOf(DocumentNotFoundException.class);

    assertThatThrownBy(() -> documents.delete(otherTenant, documentId))
        .isInstanceOf(DocumentNotFoundException.class);

    // And nothing was actually removed.
    assertThat(documentRepository.findByIdAndTenant(tenant, documentId)).isPresent();
  }

  // --- concurrency ----------------------------------------------------------

  @Test
  @DisplayName("jobs for different tenants processed in parallel do not cross tenants")
  void parallelIngestionDoesNotCrossTenants() throws IOException {
    // Both documents contain the same canary text, so the only thing that can
    // keep the results apart is the tenant filter and the explicit tenant on
    // each job row.
    String sharedCanary = "Shared Canary Marker Delta";
    Path pdfForA = writePdf(tempDir, "a.pdf", List.of("Northwind document. " + sharedCanary + "."));
    Path pdfForB = writePdf(tempDir, "b.pdf", List.of("Contoso document. " + sharedCanary + "."));

    UUID documentA = documents.upload(tenant, "Northwind Doc", "a.pdf", pdfForA);
    UUID documentB = documents.upload(otherTenant, "Contoso Doc", "b.pdf", pdfForB);

    // Claim both jobs at once and process them concurrently, each with the
    // TenantContext taken from its own job row.
    List<IngestionJobRepository.ClaimedJob> claimed = jobs.claim(10);
    assertThat(claimed).hasSize(2);

    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      claimed.stream()
          .map(job -> (Runnable) () -> ingestion.process(job))
          .map(executor::submit)
          .toList()
          .forEach(
              future -> {
                try {
                  future.get();
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
    }

    assertThat(index.countByDocument(tenant, documentA)).isPositive();
    assertThat(index.countByDocument(otherTenant, documentB)).isPositive();

    // Each tenant's search sees only its own copy of the shared canary.
    var hitsForA = index.keywordSearch(tenant, "Canary Marker", 10, VectorIndexPort.SearchFilter.none());
    var hitsForB =
        index.keywordSearch(otherTenant, "Canary Marker", 10, VectorIndexPort.SearchFilter.none());

    assertThat(hitsForA).isNotEmpty().allMatch(hit -> hit.tenantId().equals(tenant.tenantId()));
    assertThat(hitsForB).isNotEmpty().allMatch(hit -> hit.tenantId().equals(otherTenant.tenantId()));
    assertThat(hitsForA).noneMatch(hit -> hit.text().contains("Contoso"));
    assertThat(hitsForB).noneMatch(hit -> hit.text().contains("Northwind"));
  }

  // --- helpers --------------------------------------------------------------

  /** Claims and processes every queued job, as the worker would. */
  private void runPendingJobs() {
    for (int guard = 0; guard < 50; guard++) {
      List<IngestionJobRepository.ClaimedJob> claimed = jobs.claim(10);
      if (claimed.isEmpty()) {
        return;
      }
      claimed.forEach(ingestion::process);
    }
    throw new IllegalStateException("Jobs did not drain; possible retry loop");
  }

  private List<UUID> chunkIdsOf(UUID documentId) {
    return jdbc.sql("SELECT id FROM chunk WHERE document_id = :id ORDER BY chunk_index")
        .param("id", documentId)
        .query(UUID.class)
        .list();
  }

  private int versionCount(UUID documentId) {
    return jdbc.sql("SELECT count(*) FROM document_version WHERE document_id = :id")
        .param("id", documentId)
        .query(Integer.class)
        .single();
  }

  private String storageKeyOf(UUID documentId, int version) {
    return jdbc.sql(
            "SELECT storage_key FROM document_version WHERE document_id = :id AND version = :version")
        .param("id", documentId)
        .param("version", version)
        .query(String.class)
        .single();
  }
}
