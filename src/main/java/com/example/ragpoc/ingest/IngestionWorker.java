package com.example.ragpoc.ingest;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the ingestion queue and processes claimed jobs.
 *
 * <p>Each job is handed to a worker thread together with the
 * {@link com.example.ragpoc.tenant.TenantContext} built from the job row. That is
 * the whole point of using a database queue here: the tenant is an explicit
 * argument that travels with the work, rather than ambient state that a thread
 * pool would or would not inherit. The specification's concurrency test asserts
 * exactly this, and a design that relies on inheritance cannot pass it reliably.
 *
 * <p>The claim and the state change are a single {@code SKIP LOCKED} statement,
 * so two workers cannot take the same job and a crash cannot lose one silently.
 * Jobs left RUNNING by a restart are requeued on startup after a grace period.
 */
@Component
@ConditionalOnProperty(
    name = "rag.ingestion.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class IngestionWorker {

  private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

  /** How long a RUNNING job is left alone before a restart requeues it. */
  private static final int STALE_JOB_MINUTES = 15;

  private final IngestionJobRepository jobs;
  private final IngestionService ingestion;
  private final int workerThreads;
  private final Semaphore permits;
  private final ExecutorService executor;

  public IngestionWorker(
      IngestionJobRepository jobs,
      IngestionService ingestion,
      com.example.ragpoc.config.RagProperties properties) {
    this.jobs = jobs;
    this.ingestion = ingestion;
    this.workerThreads = Math.max(1, properties.ingestion().workerThreads());
    this.permits = new Semaphore(this.workerThreads);
    this.executor =
        Executors.newFixedThreadPool(this.workerThreads, new IngestionThreadFactory());
  }

  /**
   * Recovers work stranded by a restart.
   *
   * <p>A job that was RUNNING when the process died has no thread to finish it.
   * Without this it would sit RUNNING forever and its document would never
   * become answerable.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverStaleJobs() {
    int requeued = jobs.requeueStale(STALE_JOB_MINUTES);
    if (requeued > 0) {
      log.warn("Requeued {} ingestion job(s) left running by a previous process", requeued);
    }
  }

  /** Claims whatever the pool has room for and submits each job to a thread. */
  @Scheduled(fixedDelayString = "${rag.ingestion.poll-interval-ms:1000}")
  public void poll() {
    int free = permits.availablePermits();
    if (free <= 0) {
      return;
    }

    List<IngestionJobRepository.ClaimedJob> claimed = jobs.claim(free);
    for (IngestionJobRepository.ClaimedJob job : claimed) {
      // Guaranteed available: only this method claims, and it never claims more
      // than the free permit count.
      permits.acquireUninterruptibly();
      executor.execute(
          () -> {
            try {
              ingestion.process(job);
            } catch (RuntimeException e) {
              // IngestionService handles its own failures; reaching here would
              // mean a bug in that handling, so it must not kill the worker.
              log.error("Unhandled failure processing ingestion job {}", job.jobId(), e);
            } finally {
              permits.release();
            }
          });
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("Ingestion worker did not stop within 30s; interrupting in-flight work");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }

  /** Names threads so a log line or thread dump identifies the worker. */
  private static final class IngestionThreadFactory implements java.util.concurrent.ThreadFactory {
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "ingest-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
