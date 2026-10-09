package com.example.ragpoc.adapter.vector.pgvector;

import com.example.ragpoc.port.ChunkRecord;
import com.example.ragpoc.port.SearchHit;
import com.example.ragpoc.port.VectorIndexPort;
import com.example.ragpoc.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * pgvector implementation of {@link VectorIndexPort}.
 *
 * <p>Hand-written SQL rather than Spring AI's {@code VectorStore}, because the
 * search needs three things that abstraction cannot express together:
 *
 * <ol>
 *   <li>the tenant filter <em>and</em> the active-version filter in the same
 *       {@code WHERE} clause as the ranking, so foreign rows never participate
 *       in the search;
 *   <li>{@code hnsw.iterative_scan}, without which a selective filter makes HNSW
 *       return fewer rows than requested;
 *   <li>{@code tsvector} ranking for the keyword half of hybrid retrieval.
 * </ol>
 *
 * <p>The load-bearing detail is that the tenant predicate is built here, from the
 * {@link TenantContext} argument, and never accepted from the caller. There is
 * no code path through this class that issues a query against {@code chunk}
 * without it.
 */
public class PgVectorIndexAdapter implements VectorIndexPort, InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(PgVectorIndexAdapter.class);

  /**
   * Every query shares this shape: the tenant predicate is not optional, and
   * the join discards chunks belonging to a superseded version or to a document
   * that is not active. The join is what makes a version switch atomic — chunks
   * of the new version are written first, then {@code document.current_version}
   * moves, so a replacement is never visible half-applied.
   */
  private static final String TENANT_AND_ACTIVE_VERSION_PREDICATE =
      """
        FROM chunk c
        JOIN document d
          ON d.id = c.document_id
         AND d.current_version = c.version
       WHERE c.tenant_id = :tenantId
         AND d.tenant_id = :tenantId
         AND d.status = 'ACTIVE'
      """;

  private static final String HIT_COLUMNS =
      "c.id, c.tenant_id, c.document_id, c.version, c.chunk_index, c.page_start, c.page_end, "
          + "c.section_title, c.doc_title, c.text";

  private final NamedParameterJdbcTemplate namedJdbc;
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final int dimension;
  private final int efSearch;

  public PgVectorIndexAdapter(
      NamedParameterJdbcTemplate namedJdbc,
      JdbcClient jdbc,
      TransactionTemplate transactions,
      int dimension,
      int efSearch) {
    this.namedJdbc = namedJdbc;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.dimension = dimension;
    this.efSearch = Math.clamp(efSearch, 1, 10_000);
  }

  // --- schema guard ---------------------------------------------------------

  /**
   * Fails fast when the vector column's declared dimension disagrees with the
   * configured embedding model.
   *
   * <p>Two models' vectors can have the same dimension and still be
   * incomparable, but a dimension mismatch is the detectable case, and mixing
   * geometry silently is precisely the accident that produces plausible-looking,
   * meaningless rankings. Refusing to start is the only safe response.
   */
  @Override
  public void afterPropertiesSet() {
    String declared =
        jdbc.sql(
                """
                SELECT format_type(a.atttypid, a.atttypmod)
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE c.relname = 'chunk'
                   AND a.attname = 'embedding'
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                   AND n.nspname = current_schema()
                """)
            .query(String.class)
            .optional()
            .orElse(null);

    if (declared == null) {
      throw new IllegalStateException(
          "The 'chunk' table has no 'embedding' column. Is the pgvector migration location "
              + "(classpath:db/vector/pgvector) enabled and has Flyway run?");
    }

    String expected = "vector(" + dimension + ")";
    if (!expected.equals(declared)) {
      throw new IllegalStateException(
          "Embedding dimension mismatch: the 'chunk.embedding' column is "
              + declared
              + " but rag.embedding.dimension is "
              + dimension
              + ". Vectors from different models must never share an index; "
              + "changing the embedding model requires a re-index into a new column or table, "
              + "not a configuration change.");
    }
    log.info("pgvector index ready: embedding column is {}, ef_search {}", declared, efSearch);
  }

  // --- writes ---------------------------------------------------------------

  @Override
  public void upsert(TenantContext tenant, List<ChunkRecord> chunks) {
    if (chunks.isEmpty()) {
      return;
    }

    String sql =
        """
        INSERT INTO chunk (id, tenant_id, document_id, version, chunk_index, page_start, page_end,
                           section_title, doc_title, text, embedding_model, embedding)
        VALUES (:id, :tenantId, :documentId, :version, :chunkIndex, :pageStart, :pageEnd,
                :sectionTitle, :docTitle, :text, :embeddingModel, CAST(:embedding AS vector))
        ON CONFLICT (id) DO UPDATE
           SET text            = EXCLUDED.text,
               section_title   = EXCLUDED.section_title,
               doc_title       = EXCLUDED.doc_title,
               embedding_model = EXCLUDED.embedding_model,
               embedding       = EXCLUDED.embedding
        """;

    MapSqlParameterSource[] batch =
        chunks.stream()
            .map(chunk -> toParameters(tenant, chunk))
            .toArray(MapSqlParameterSource[]::new);

    namedJdbc.batchUpdate(sql, batch);
  }

  /**
   * The tenant is written from the context, never from the payload: a
   * {@link ChunkRecord} has no tenant field, so a mismatched pairing cannot even
   * be expressed.
   *
   * <p>The conflict clause deliberately does not reassign {@code tenant_id},
   * {@code document_id} or {@code version}: those form the chunk's identity, and
   * letting a re-ingest move a chunk to another tenant would be a hole rather
   * than a feature.
   */
  private MapSqlParameterSource toParameters(TenantContext tenant, ChunkRecord chunk) {
    return new MapSqlParameterSource()
        .addValue("id", chunk.chunkId())
        .addValue("tenantId", tenant.tenantId())
        .addValue("documentId", chunk.documentId())
        .addValue("version", chunk.version())
        .addValue("chunkIndex", chunk.chunkIndex())
        .addValue("pageStart", chunk.pageStart())
        .addValue("pageEnd", chunk.pageEnd())
        .addValue("sectionTitle", chunk.sectionTitle())
        .addValue("docTitle", chunk.docTitle())
        .addValue("text", chunk.text())
        .addValue("embeddingModel", chunk.embeddingModel())
        .addValue("embedding", toVectorLiteral(chunk.embedding()));
  }

  @Override
  public int deleteByDocument(TenantContext tenant, UUID documentId) {
    return jdbc.sql("DELETE FROM chunk WHERE tenant_id = :tenantId AND document_id = :documentId")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .update();
  }

  @Override
  public int countByDocument(TenantContext tenant, UUID documentId) {
    return jdbc.sql("SELECT count(*) FROM chunk WHERE tenant_id = :tenantId AND document_id = :documentId")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .query(Integer.class)
        .single();
  }

  @Override
  public int deleteByDocumentVersion(TenantContext tenant, UUID documentId, int version) {
    return jdbc.sql(
            "DELETE FROM chunk WHERE tenant_id = :tenantId AND document_id = :documentId "
                + "AND version = :version")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .param("version", version)
        .update();
  }

  // --- search ---------------------------------------------------------------

  @Override
  public List<SearchHit> denseSearch(
      TenantContext tenant, float[] queryVector, int limit, SearchFilter filter) {
    String vector = toVectorLiteral(queryVector);

    String sql =
        "SELECT "
            + HIT_COLUMNS
            + ", 1 - (c.embedding <=> CAST(:queryVector AS vector)) AS score\n"
            + TENANT_AND_ACTIVE_VERSION_PREDICATE
            + documentFilterClause(filter)
            + " ORDER BY c.embedding <=> CAST(:queryVector AS vector)\n"
            + " LIMIT :limit";

    return transactions.execute(
        status -> {
          applyHnswSettings();
          JdbcClient.StatementSpec statement =
              jdbc.sql(sql)
                  .param("tenantId", tenant.tenantId())
                  .param("queryVector", vector)
                  .param("limit", limit);
          if (!filter.isEmpty()) {
            statement = statement.param("documentIds", filter.documentIds());
          }
          return statement.query((rs, rowNum) -> mapHit(rs, SearchHit.Source.DENSE)).list();
        });
  }

  @Override
  public List<SearchHit> keywordSearch(
      TenantContext tenant, String queryText, int limit, SearchFilter filter) {

    String sql =
        "SELECT "
            + HIT_COLUMNS
            + ", ts_rank_cd(c.tsv, websearch_to_tsquery('english', :queryText)) AS score\n"
            + TENANT_AND_ACTIVE_VERSION_PREDICATE
            + "   AND c.tsv @@ websearch_to_tsquery('english', :queryText)\n"
            + documentFilterClause(filter)
            + " ORDER BY score DESC, c.id\n"
            + " LIMIT :limit";

    JdbcClient.StatementSpec statement =
        jdbc.sql(sql)
            .param("tenantId", tenant.tenantId())
            .param("queryText", queryText == null ? "" : queryText)
            .param("limit", limit);
    if (!filter.isEmpty()) {
      statement = statement.param("documentIds", filter.documentIds());
    }
    return statement.query((rs, rowNum) -> mapHit(rs, SearchHit.Source.KEYWORD)).list();
  }

  private static String documentFilterClause(SearchFilter filter) {
    return filter.isEmpty() ? "" : "   AND c.document_id IN (:documentIds)\n";
  }

  /**
   * Enables iterative index scans for this transaction.
   *
   * <p>With a selective filter (one tenant among many), a plain HNSW scan
   * explores the graph and collects matches until it has {@code k} rows or
   * exhausts the entry point, which can return far fewer rows than requested
   * even though matching rows exist. Iterative scanning keeps going until the
   * limit is met. {@code SET LOCAL} rather than {@code SET} so the setting
   * cannot leak to the next request that borrows this pooled connection.
   */
  private void applyHnswSettings() {
    jdbc.sql("SET LOCAL hnsw.iterative_scan = strict_order").update();
    jdbc.sql("SET LOCAL hnsw.ef_search = " + efSearch).update();
  }

  // --- mapping --------------------------------------------------------------

  private static SearchHit mapHit(ResultSet rs, SearchHit.Source source) throws SQLException {
    return new SearchHit(
        rs.getObject("id", UUID.class),
        // From the row, not from the request: RetrievalService compares this
        // against the caller's tenant and aborts on any mismatch.
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("document_id", UUID.class),
        rs.getInt("version"),
        rs.getInt("chunk_index"),
        rs.getInt("page_start"),
        rs.getInt("page_end"),
        rs.getString("section_title"),
        rs.getString("doc_title"),
        rs.getString("text"),
        rs.getDouble("score"),
        source);
  }

  /**
   * Renders a vector as pgvector's text form, e.g. {@code [0.1,-0.2]}.
   *
   * <p>Avoids pulling in the pgvector JDBC type mapping for a value that is only
   * ever written and never read back on this path. The SQL casts the parameter
   * explicitly, so this cannot be confused with a text column.
   */
  static String toVectorLiteral(float[] embedding) {
    if (embedding == null || embedding.length == 0) {
      throw new IllegalArgumentException("embedding must not be empty");
    }
    StringBuilder literal = new StringBuilder(embedding.length * 8).append('[');
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        literal.append(',');
      }
      // Float.toString gives a round-trippable representation.
      literal.append(embedding[i]);
    }
    return literal.append(']').toString();
  }
}
