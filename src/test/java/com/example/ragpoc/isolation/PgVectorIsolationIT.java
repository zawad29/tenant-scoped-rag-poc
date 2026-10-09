package com.example.ragpoc.isolation;

import java.io.IOException;
import java.nio.file.Files;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the tenant-isolation contract against the pgvector adapter.
 *
 * <p>This class does nothing but choose the storage backend, which is the point:
 * the contract itself lives in the base class, so a second adapter cannot pass a
 * weaker suite than the first. When the Qdrant adapter arrives, its test class
 * extends the same contract and changes only the {@code rag.vector.provider}
 * property.
 *
 * <p>The ingestion worker is disabled so that jobs are claimed and run
 * explicitly, which keeps the assertions deterministic rather than timing
 * dependent. The claim query and the worker are exercised by
 * {@code IngestionPipelineIT}.
 */
@SpringBootTest
class PgVectorIsolationIT extends AbstractTenantIsolationContractTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerDatasourceProperties(registry);
    registry.add("rag.vector.provider", () -> "pgvector");
    registry.add("rag.ingestion.worker-enabled", () -> "false");
    registry.add(
        "rag.storage.local-root",
        () -> {
          try {
            // Fail loudly here rather than letting uploads fail later with a
            // confusing message.
            Files.createDirectories(AbstractTenantIsolationContractTest.storageRoot());
          } catch (IOException e) {
            throw new IllegalStateException("Could not create the isolation test storage root", e);
          }
          return AbstractTenantIsolationContractTest.storageRoot().toString();
        });
  }
}
