package com.example.ragpoc.ingest;

import com.example.ragpoc.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ingestion queue.
 *
 * <p>A database-backed queue rather than {@code @Async}, for three reasons that
 * all matter to this PoC:
 *
 * <ol>
 *   <li>work survives a restart, where an in-memory task would be lost;
 *   <li>{@code attempts} gives retries a defined semantics rather than an
 *       infinite loop;
 *   <li>the tenant travels <em>on the row</em>, so a worker thread receives it as
 *       an explicit argument. Inherited context is exactly the mechanism that
 *       leaks tenants across threads, and the specification's test 15.1.7 asks
 *       for the opposite.
 * </ol>
 */
@Repository
public class IngestionJobRepository {

  private final JdbcClient jdbc;

  public IngestionJobRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public UUID enqueue(TenantContext tenant, UUID documentId, int version) {
    UUID jobId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO ingestion_job (id, tenant_id, document_id, version, state)
            VALUES (:id, :tenantId, :documentId, :version, 'QUEUED')
            """)
        .param("id", jobId)
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .param("version", version)
        .update();
    return jobId;
  }

  /** True when the document already has queued or running work. */
  public boolean hasPendingJob(TenantContext tenant, UUID documentId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM ingestion_job
             WHERE tenant_id = :tenantId
               AND document_id = :documentId
               AND state IN ('QUEUED', 'RUNNING')
            """)
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .query(Integer.class)
        .single()
        > 0;
  }

  /**
   * Claims up to {@code limit} queued jobs.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} is what makes this safe with more than one
   * worker, or with a worker that overlaps its own previous run: each claimer
   * takes rows no one else holds, and no row is processed twice. The claim and
   * the state change are one statement, so a crash between them is impossible.
   */
  public List<ClaimedJob> claim(int limit) {
    return jdbc.sql(
            """
            WITH claimable AS (
                SELECT id
                  FROM ingestion_job
                 WHERE state = 'QUEUED'
                 ORDER BY created_at
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE ingestion_job job
               SET state = 'RUNNING',
                   claimed_at = now(),
                   started_at = now(),
                   attempts = job.attempts + 1
              FROM claimable
             WHERE job.id = claimable.id
            RETURNING job.id, job.tenant_id, job.document_id, job.version, job.attempts
            """)
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new ClaimedJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("document_id", UUID.class),
                    rs.getInt("version"),
                    rs.getInt("attempts")))
        .list();
  }

  public void markSucceeded(UUID jobId) {
    jdbc.sql(
            "UPDATE ingestion_job SET state = 'SUCCEEDED', finished_at = now(), error = NULL "
                + "WHERE id = :id")
        .param("id", jobId)
        .update();
  }

  /**
   * Records a failure, requeueing when the attempt budget allows.
   *
   * <p>A rejection such as a scanned PDF is not retried: the input will not
   * change, so retrying would burn attempts and keep the document showing as
   * in-progress. Only unexpected failures are retried.
   */
  public void markFailed(UUID jobId, String error, boolean retryable, int maxAttempts) {
    jdbc.sql(
            """
            UPDATE ingestion_job
               SET state = CASE WHEN :retryable AND attempts < :maxAttempts THEN 'QUEUED' ELSE 'FAILED' END,
                   error = :error,
                   claimed_at = NULL,
                   finished_at = CASE WHEN :retryable AND attempts < :maxAttempts THEN NULL ELSE now() END
             WHERE id = :id
            """)
        .param("retryable", retryable)
        .param("maxAttempts", maxAttempts)
        .param("error", truncate(error, 2000))
        .param("id", jobId)
        .update();
  }

  /** Requeues jobs left RUNNING by a crash, so a restart does not strand work. */
  public int requeueStale(int olderThanMinutes) {
    return jdbc.sql(
            """
            UPDATE ingestion_job
               SET state = 'QUEUED', claimed_at = NULL
             WHERE state = 'RUNNING'
               AND claimed_at < now() - make_interval(mins => :minutes)
            """)
        .param("minutes", olderThanMinutes)
        .update();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  /**
   * A job claimed for processing.
   *
   * <p>{@code tenantId} is read from the row and turned into a
   * {@link TenantContext} by the worker. That is the explicit propagation: no
   * part of the worker needs to know which request caused the work.
   */
  public record ClaimedJob(
      UUID jobId, UUID tenantId, UUID documentId, int version, int attempts) {}
}
