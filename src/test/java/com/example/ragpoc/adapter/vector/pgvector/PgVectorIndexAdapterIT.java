package com.example.ragpoc.adapter.vector.pgvector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragpoc.port.ChunkRecord;
import com.example.ragpoc.port.SearchHit;
import com.example.ragpoc.port.VectorIndexPort;
import com.example.ragpoc.support.PostgresTestSupport;
import com.example.ragpoc.tenant.Role;
import com.example.ragpoc.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Behaviour of the pgvector adapter against a real PostgreSQL with pgvector.
 *
 * <p>The tenant-filter checks here are the ones the specification's isolation
 * suite depends on, tested at the level where the filter is actually built. If
 * the filter is wrong, it is wrong here first.
 */
@SpringBootTest
class PgVectorIndexAdapterIT extends PostgresTestSupport {

  private static final int DIMENSION = 768;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerDatasourceProperties(registry);
  }

  @Autowired private VectorIndexPort index;
  @Autowired private DataSource dataSource;

  private JdbcClient jdbc;
  private TenantContext tenantA;
  private TenantContext tenantB;

  @BeforeEach
  void setUp() {
    resetDatabase(dataSource);
    jdbc = JdbcClient.create(dataSource);

    tenantA = TenantContext.of(seedTenant(dataSource, "Northwind"), null, Role.TENANT_ADMIN);
    tenantB = TenantContext.of(seedTenant(dataSource, "Contoso"), null, Role.TENANT_ADMIN);
  }

  // --- tenant isolation at the adapter boundary -----------------------------

  @Test
  @DisplayName("a search returns only the calling tenant's chunks")
  void searchIsScopedToTheCallingTenant() {
    UUID documentA = seedActiveDocument(tenantA, 1);
    UUID documentB = seedActiveDocument(tenantB, 1);

    index.upsert(tenantA, List.of(chunk(documentA, 1, 0, "Northwind annual leave policy.", e0())));
    index.upsert(tenantB, List.of(chunk(documentB, 1, 0, "Contoso payroll policy.", e0())));

    List<SearchHit> hits = index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.none());

    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(hit -> hit.tenantId().equals(tenantA.tenantId()));
    assertThat(hits).noneMatch(hit -> hit.text().contains("Contoso"));
  }

  @Test
  @DisplayName("one tenant cannot delete another tenant's chunks")
  void deleteIsScopedToTheCallingTenant() {
    UUID documentA = seedActiveDocument(tenantA, 1);
    index.upsert(tenantA, List.of(chunk(documentA, 1, 0, "Northwind content.", e0())));

    // Tenant B attempts to delete a document id that belongs to tenant A.
    int deleted = index.deleteByDocument(tenantB, documentA);

    assertThat(deleted).as("the delete must not affect another tenant's rows").isZero();
    assertThat(index.countByDocument(tenantA, documentA)).isEqualTo(1);
  }

  @Test
  @DisplayName("counting is scoped to the calling tenant")
  void countIsScopedToTheCallingTenant() {
    UUID documentA = seedActiveDocument(tenantA, 1);
    index.upsert(tenantA, List.of(chunk(documentA, 1, 0, "Northwind content.", e0())));

    assertThat(index.countByDocument(tenantA, documentA)).isEqualTo(1);
    assertThat(index.countByDocument(tenantB, documentA)).isZero();
  }

  @Test
  @DisplayName("the upsert writes the tenant from the context, not the payload")
  void upsertAttributesChunksToTheContextTenant() {
    UUID document = seedActiveDocument(tenantA, 1);

    // The same chunk id is offered by both tenants. ChunkRecord has no tenant
    // field, so the only thing that decides the owner is the context argument.
    index.upsert(tenantA, List.of(chunk(document, 1, 0, "Northwind content.", e0())));

    UUID storedTenant =
        jdbc.sql("SELECT tenant_id FROM chunk WHERE id = :id")
            .param("id", chunkId(document, 1, 0))
            .query(UUID.class)
            .single();

    assertThat(storedTenant).isEqualTo(tenantA.tenantId());
  }

  // --- active version -------------------------------------------------------

  @Test
  @DisplayName("only chunks of the active version are searchable")
  void onlyActiveVersionIsSearchable() {
    UUID document = seedActiveDocument(tenantA, 2);
    index.upsert(
        tenantA,
        List.of(
            chunk(document, 1, 0, "Superseded version text.", e0()),
            chunk(document, 2, 0, "Current version text.", e0())));

    List<SearchHit> hits = index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.none());

    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(hit -> hit.version() == 2);
    assertThat(hits).noneMatch(hit -> hit.text().contains("Superseded"));
  }

  @Test
  @DisplayName("a document that is not ACTIVE is not searchable")
  void inactiveDocumentIsNotSearchable() {
    UUID document = seedActiveDocument(tenantA, 1);
    index.upsert(tenantA, List.of(chunk(document, 1, 0, "Content of a failed document.", e0())));

    jdbc.sql("UPDATE document SET status = 'FAILED' WHERE id = :id").param("id", document).update();

    assertThat(index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.none())).isEmpty();
  }

  @Test
  @DisplayName("keyword search honours the same tenant and version restrictions")
  void keywordSearchHonoursTenantAndVersion() {
    UUID documentA = seedActiveDocument(tenantA, 1);
    UUID documentB = seedActiveDocument(tenantB, 1);
    index.upsert(tenantA, List.of(chunk(documentA, 1, 0, "Zephyr quokka protocol details.", e0())));
    index.upsert(tenantB, List.of(chunk(documentB, 1, 0, "Zephyr quokka protocol details.", e0())));

    List<SearchHit> hits =
        index.keywordSearch(tenantA, "zephyr quokka", 10, VectorIndexPort.SearchFilter.none());

    assertThat(hits).isNotEmpty();
    assertThat(hits).allMatch(hit -> hit.tenantId().equals(tenantA.tenantId()));
  }

  // --- behaviour ------------------------------------------------------------

  @Test
  @DisplayName("upsert is idempotent, so a retried ingestion does not duplicate chunks")
  void upsertIsIdempotent() {
    UUID document = seedActiveDocument(tenantA, 1);
    ChunkRecord record = chunk(document, 1, 0, "Original text.", e0());

    index.upsert(tenantA, List.of(record));
    // Same deterministic id, changed text: a retry after a partial failure.
    index.upsert(tenantA, List.of(chunk(document, 1, 0, "Reprocessed text.", e0())));

    assertThat(index.countByDocument(tenantA, document)).isEqualTo(1);

    List<SearchHit> hits = index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.none());
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().text()).isEqualTo("Reprocessed text.");
  }

  @Test
  @DisplayName("dense search orders by similarity and reports it as a score")
  void denseSearchOrdersBySimilarity() {
    UUID document = seedActiveDocument(tenantA, 1);
    index.upsert(
        tenantA,
        List.of(
            chunk(document, 1, 0, "Exact match.", e0()),
            chunk(document, 1, 1, "Orthogonal.", e1()),
            chunk(document, 1, 2, "Opposite.", eNegative())));

    List<SearchHit> hits = index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.none());

    assertThat(hits).hasSize(3);
    assertThat(hits.get(0).text()).isEqualTo("Exact match.");
    assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    assertThat(hits.get(1).score()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-5));
    assertThat(hits.get(2).score()).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-5));
  }

  @Test
  @DisplayName("a search restricted to one document does not return another's chunks")
  void documentFilterIsApplied() {
    UUID first = seedActiveDocument(tenantA, 1);
    UUID second = seedActiveDocument(tenantA, 1);
    index.upsert(tenantA, List.of(chunk(first, 1, 0, "First document.", e0())));
    index.upsert(tenantA, List.of(chunk(second, 1, 0, "Second document.", e0())));

    List<SearchHit> hits =
        index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.ofDocument(second));

    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().documentId()).isEqualTo(second);
  }

  @Test
  @DisplayName("deleting a document removes its chunks and reports the count")
  void deleteRemovesChunks() {
    UUID document = seedActiveDocument(tenantA, 1);
    index.upsert(
        tenantA,
        List.of(
            chunk(document, 1, 0, "One.", e0()),
            chunk(document, 1, 1, "Two.", e0()),
            chunk(document, 1, 2, "Three.", e0())));

    assertThat(index.deleteByDocument(tenantA, document)).isEqualTo(3);
    assertThat(index.countByDocument(tenantA, document)).isZero();
    assertThat(index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.none())).isEmpty();
  }

  @Test
  @DisplayName("scores from separate tenants are never mixed in one result set")
  void concurrentTenantsDoNotShareResults() {
    // Interleaved writes from two tenants, then searches from both. The claim is
    // not about database concurrency but about the filter: each tenant sees
    // exactly its own rows and no others.
    UUID documentA = seedActiveDocument(tenantA, 1);
    UUID documentB = seedActiveDocument(tenantB, 1);
    List<ChunkRecord> chunksA = new ArrayList<>();
    List<ChunkRecord> chunksB = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      chunksA.add(chunk(documentA, 1, i, "Northwind chunk number " + i + ".", e0()));
      chunksB.add(chunk(documentB, 1, i, "Contoso chunk number " + i + ".", e0()));
    }
    index.upsert(tenantA, chunksA);
    index.upsert(tenantB, chunksB);

    List<SearchHit> hitsA = index.denseSearch(tenantA, e0(), 50, VectorIndexPort.SearchFilter.none());
    List<SearchHit> hitsB = index.denseSearch(tenantB, e0(), 50, VectorIndexPort.SearchFilter.none());

    assertThat(hitsA).hasSize(25);
    assertThat(hitsB).hasSize(25);
    assertThat(hitsA).allMatch(hit -> hit.text().startsWith("Northwind"));
    assertThat(hitsB).allMatch(hit -> hit.text().startsWith("Contoso"));
  }

  @Test
  @DisplayName("recall holds when the tenant filter is highly selective")
  void recallHoldsUnderASelectiveFilter() {
    // The failure this guards against: a selective filter plus an HNSW index can
    // return fewer rows than requested even when matching rows exist. Without
    // iterative scans, tenant A's k=10 search would come back short.
    UUID smallTenantDocument = seedActiveDocument(tenantA, 1);
    List<ChunkRecord> few = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      few.add(chunk(smallTenantDocument, 1, i, "Northwind item " + i + ".", e0()));
    }
    index.upsert(tenantA, few);

    UUID largeTenantDocument = seedActiveDocument(tenantB, 1);
    List<ChunkRecord> many = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      many.add(chunk(largeTenantDocument, 1, i, "Contoso item " + i + ".", e0()));
    }
    index.upsert(tenantB, many);

    List<SearchHit> hits = index.denseSearch(tenantA, e0(), 10, VectorIndexPort.SearchFilter.none());

    assertThat(hits)
        .as("a selective tenant filter must still return the requested number of rows")
        .hasSize(10);
  }

  // --- dimension guard ------------------------------------------------------

  @Test
  @DisplayName("a mismatched embedding dimension stops the application instead of mixing vectors")
  void mismatchedDimensionIsRefusedAtStartup() {
    RagPropertiesWithWrongDimensionProbe probe =
        new RagPropertiesWithWrongDimensionProbe(dataSource, 384);

    assertThatThrownBy(probe::verify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dimension mismatch")
        .hasMessageContaining("re-index");
  }

  /** Invokes the adapter's startup check with a deliberately wrong dimension. */
  private static final class RagPropertiesWithWrongDimensionProbe {
    private final DataSource dataSource;
    private final int dimension;

    RagPropertiesWithWrongDimensionProbe(DataSource dataSource, int dimension) {
      this.dataSource = dataSource;
      this.dimension = dimension;
    }

    void verify() {
      var adapter =
          new PgVectorIndexAdapter(
              new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource),
              JdbcClient.create(dataSource),
              new org.springframework.transaction.support.TransactionTemplate(
                  new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)),
              dimension,
              100);
      adapter.afterPropertiesSet();
    }
  }

  // --- seeding helpers ------------------------------------------------------

  private UUID seedActiveDocument(TenantContext tenant, int currentVersion) {
    UUID documentId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO document (id, tenant_id, title, original_filename, status, current_version)
            VALUES (:id, :tenantId, :title, 'policy.pdf', 'ACTIVE', :version)
            """)
        .param("id", documentId)
        .param("tenantId", tenant.tenantId())
        .param("title", "Policy " + documentId)
        .param("version", currentVersion)
        .update();
    jdbc.sql(
            """
            INSERT INTO document_version
                   (id, document_id, tenant_id, version, storage_key, content_hash, chunk_count)
            VALUES (:id, :documentId, :tenantId, :version, :storageKey, 'hash', 0)
            """)
        .param("id", UUID.randomUUID())
        .param("documentId", documentId)
        .param("tenantId", tenant.tenantId())
        .param("version", currentVersion)
        .param("storageKey", tenant.tenantId() + "/" + documentId + "/" + currentVersion)
        .update();
    return documentId;
  }

  private static UUID chunkId(UUID documentId, int version, int chunkIndex) {
    return UUID.nameUUIDFromBytes(
        (documentId + ":" + version + ":" + chunkIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static ChunkRecord chunk(UUID documentId, int version, int index, String text, float[] embedding) {
    return new ChunkRecord(
        chunkId(documentId, version, index),
        documentId,
        version,
        index,
        1,
        1,
        null,
        "Policy Document",
        text,
        "test-model",
        embedding);
  }

  /** Unit vector along dimension 0. */
  private static float[] e0() {
    float[] v = new float[DIMENSION];
    v[0] = 1f;
    return v;
  }

  /** Unit vector along a different dimension: cosine similarity 0. */
  private static float[] e1() {
    float[] v = new float[DIMENSION];
    v[1] = 1f;
    return v;
  }

  /** Opposite direction: cosine similarity -1. */
  private static float[] eNegative() {
    float[] v = new float[DIMENSION];
    v[0] = -1f;
    return v;
  }
}
