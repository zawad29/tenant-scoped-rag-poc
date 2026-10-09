package com.example.ragpoc.document;

import com.example.ragpoc.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Document persistence.
 *
 * <p>Every read and every write takes a {@link TenantContext} and includes
 * {@code tenant_id} in the statement. There is deliberately no unscoped finder:
 * routes resolve identifiers that arrive from the browser, so an unscoped
 * {@code findById} would be a direct IDOR waiting for a caller to forget the
 * check.
 */
@Repository
public class DocumentRepository {

  private static final String COLUMNS =
      "id, tenant_id, title, original_filename, content_hash, status, current_version, "
          + "page_count, created_at, updated_at";

  private final JdbcClient jdbc;

  public DocumentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Document> findByIdAndTenant(TenantContext tenant, UUID documentId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM document WHERE id = :id AND tenant_id = :tenantId")
        .param("id", documentId)
        .param("tenantId", tenant.tenantId())
        .query(DocumentRepository::map)
        .optional();
  }

  public List<Document> listByTenant(TenantContext tenant) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM document WHERE tenant_id = :tenantId ORDER BY created_at DESC")
        .param("tenantId", tenant.tenantId())
        .query(DocumentRepository::map)
        .list();
  }

  /**
   * Lists documents with their most recent ingestion job, for the admin screen.
   *
   * <p>One query rather than a document list plus a job lookup per row: the
   * latter is a classic N+1 that only shows up once a tenant has a few hundred
   * documents.
   */
  public List<DocumentSummary> listSummaries(TenantContext tenant) {
    return jdbc.sql(
            """
            SELECT d.id,
                   d.title,
                   d.original_filename,
                   d.status,
                   d.current_version,
                   d.page_count,
                   d.updated_at,
                   v.chunk_count,
                   j.state        AS job_state,
                   j.error        AS job_error,
                   j.attempts     AS job_attempts
              FROM document d
              LEFT JOIN document_version v
                     ON v.document_id = d.id
                    AND v.version = d.current_version
              LEFT JOIN LATERAL (
                     SELECT state, error, attempts
                       FROM ingestion_job
                      WHERE document_id = d.id
                      ORDER BY created_at DESC
                      LIMIT 1
                   ) j ON true
             WHERE d.tenant_id = :tenantId
             ORDER BY d.created_at DESC
            """)
        .param("tenantId", tenant.tenantId())
        .query(
            (rs, rowNum) ->
                new DocumentSummary(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("original_filename"),
                    DocumentStatus.valueOf(rs.getString("status")),
                    rs.getInt("current_version"),
                    rs.getObject("page_count", Integer.class),
                    rs.getObject("chunk_count", Integer.class),
                    rs.getString("job_state"),
                    rs.getString("job_error"),
                    rs.getInt("job_attempts"),
                    rs.getTimestamp("updated_at").toInstant()))
        .list();
  }

  /** @return whether a row was actually inserted, so callers can log a no-op */
  public void insert(Document document) {
    jdbc.sql(
            """
            INSERT INTO document (id, tenant_id, title, original_filename, content_hash, status,
                                  current_version, page_count, updated_at)
            VALUES (:id, :tenantId, :title, :originalFilename, :contentHash, :status,
                    :currentVersion, :pageCount, now())
            """)
        .param("id", document.id())
        .param("tenantId", document.tenantId())
        .param("title", document.title())
        .param("originalFilename", document.originalFilename())
        .param("contentHash", document.contentHash())
        .param("status", document.status().name())
        .param("currentVersion", document.currentVersion())
        .param("pageCount", document.pageCount())
        .update();
  }

  /**
   * Marks a first-time ingestion as in progress.
   *
   * <p>Guarded by {@code current_version = 0}, and that guard is load-bearing.
   * A status of PROCESSING removes the document from search, because the adapter
   * requires {@code document.status = 'ACTIVE'}. For a replacement that would
   * mean the old version stops answering the moment the new one starts
   * ingesting, so an update would cause an outage for the very document being
   * updated. A document that already has a version stays ACTIVE and keeps
   * answering from it; the admin list shows progress from the job state instead.
   */
  public void markProcessing(TenantContext tenant, UUID documentId) {
    jdbc.sql(
            """
            UPDATE document SET status = 'PROCESSING', updated_at = now()
             WHERE id = :documentId AND tenant_id = :tenantId AND current_version = 0
            """)
        .param("documentId", documentId)
        .param("tenantId", tenant.tenantId())
        .update();
  }

  /**
   * Marks a failed first-time ingestion. As with {@link #markProcessing}, a
   * document with a working version is left ACTIVE so a failed replacement does
   * not take the existing answers away.
   */
  public void markFailed(TenantContext tenant, UUID documentId) {
    jdbc.sql(
            """
            UPDATE document SET status = 'FAILED', updated_at = now()
             WHERE id = :documentId AND tenant_id = :tenantId AND current_version = 0
            """)
        .param("documentId", documentId)
        .param("tenantId", tenant.tenantId())
        .update();
  }

  public void markDeleting(TenantContext tenant, UUID documentId) {
    updateStatus(tenant, documentId, DocumentStatus.DELETING, "updated_at = now()");
  }
  /**
   * Makes a version answerable.
   *
   * <p>This single update is what switches a replacement over: the new version's
   * chunks were already written, and queries join on
   * {@code current_version = chunk.version}, so the switch is atomic and no
   * chunk row is rewritten. Users never see a half-indexed document, and they do
   * not lose answers while an update runs.
   */
  public void activate(
      TenantContext tenant, UUID documentId, int version, String contentHash, int pageCount) {
    int updated =
        jdbc.sql(
                """
                UPDATE document
                   SET status = 'ACTIVE',
                       current_version = :version,
                       content_hash = :contentHash,
                       page_count = :pageCount,
                       updated_at = now()
                 WHERE id = :documentId
                   AND tenant_id = :tenantId
                """)
            .param("version", version)
            .param("contentHash", contentHash)
            .param("pageCount", pageCount)
            .param("documentId", documentId)
            .param("tenantId", tenant.tenantId())
            .update();
    if (updated == 0) {
      throw new IllegalStateException("Document disappeared while it was being ingested");
    }
  }

  public int deleteByIdAndTenant(TenantContext tenant, UUID documentId) {
    return jdbc.sql("DELETE FROM document WHERE id = :id AND tenant_id = :tenantId")
        .param("id", documentId)
        .param("tenantId", tenant.tenantId())
        .update();
  }

  private void updateStatus(
      TenantContext tenant, UUID documentId, DocumentStatus status, String extraSet) {
    jdbc.sql(
            "UPDATE document SET status = :status, "
                + extraSet
                + " WHERE id = :documentId AND tenant_id = :tenantId")
        .param("status", status.name())
        .param("documentId", documentId)
        .param("tenantId", tenant.tenantId())
        .update();
  }

  private static Document map(ResultSet rs, int rowNum) throws SQLException {
    return new Document(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("title"),
        rs.getString("original_filename"),
        rs.getString("content_hash"),
        DocumentStatus.valueOf(rs.getString("status")),
        rs.getInt("current_version"),
        rs.getObject("page_count", Integer.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  /** A document plus the state of its latest ingestion, for the admin list. */
  public record DocumentSummary(
      UUID id,
      String title,
      String originalFilename,
      DocumentStatus status,
      int currentVersion,
      Integer pageCount,
      Integer chunkCount,
      String jobState,
      String jobError,
      int jobAttempts,
      Instant updatedAt) {

    /** True while the row should keep refreshing itself. */
    public boolean inProgress() {
      return status == DocumentStatus.UPLOADED
          || status == DocumentStatus.PROCESSING
          || status == DocumentStatus.DELETING
          || "QUEUED".equals(jobState)
          || "RUNNING".equals(jobState);
    }
  }
}
