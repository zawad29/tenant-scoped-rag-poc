package com.example.ragpoc.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.qdrant.QdrantContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need the real stores.
 *
 * <p>Real PostgreSQL for structured data and a real Qdrant for vectors, not
 * embedded substitutes: the isolation suite exists to prove things about the
 * actual query and filter behaviour, which a substitute would not.
 *
 * <p>The containers start once per JVM and are shared by every subclass.
 */
public abstract class IntegrationTestSupport {

  public static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("postgres:17").asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ragpoc")
          .withUsername("ragpoc")
          .withPassword("ragpoc")
          .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off");

  public static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:latest");

  private static final Path STORAGE_ROOT = createStorageRoot();

  private static Path createStorageRoot() {
    try {
      return Files.createTempDirectory("cidrag-storage-");
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Could not create a temporary storage root", e);
    }
  }

  static {
    POSTGRES.start();
    QDRANT.start();
  }

  /** Registers datasource and Qdrant settings, and disables the background worker. */
  protected static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("rag.qdrant.host", QDRANT::getHost);
    registry.add("rag.qdrant.port", QDRANT::getGrpcPort);
    registry.add("rag.storage.local-root", () -> STORAGE_ROOT.toString());
  }

  // --- seeding --------------------------------------------------------------

  protected static UUID seedCid(DataSource dataSource, String name) {
    UUID cidId = UUID.randomUUID();
    JdbcClient.create(dataSource)
        .sql("INSERT INTO cid (id, name, status) VALUES (:id, :name, 'ACTIVE')")
        .param("id", cidId)
        .param("name", name)
        .update();
    return cidId;
  }

  /**
   * Inserts a cid plus one user and returns the user's id. Tests write rows
   * directly rather than through services, so a service bug cannot make an
   * isolation test pass.
   */
  protected static UUID seedCidWithUser(
      DataSource dataSource,
      PasswordEncoder passwordEncoder,
      String cidName,
      String email,
      String rawPassword,
      String role) {

    UUID cidId = seedCid(dataSource, cidName);
    UUID userId = UUID.randomUUID();
    JdbcClient.create(dataSource)
        .sql(
            """
            INSERT INTO app_user (id, cid_id, email, password_hash, role, enabled)
            VALUES (:id, :cidId, :email, :hash, :role, true)
            """)
        .param("id", userId)
        .param("cidId", cidId)
        .param("email", email)
        .param("hash", passwordEncoder.encode(rawPassword))
        .param("role", role)
        .update();
    return userId;
  }

  protected static UUID cidIdOf(DataSource dataSource, String email) {
    return JdbcClient.create(dataSource)
        .sql("SELECT cid_id FROM app_user WHERE lower(email) = lower(:email)")
        .param("email", email)
        .query(UUID.class)
        .single();
  }

  protected static UUID userIdOf(DataSource dataSource, String email) {
    return JdbcClient.create(dataSource)
        .sql("SELECT id FROM app_user WHERE lower(email) = lower(:email)")
        .param("email", email)
        .query(UUID.class)
        .single();
  }

  /**
   * Empties every cid-owned table between tests. Truncating {@code cid} cascades
   * to users, documents, versions and ingestion jobs; {@code audit_event} has a
   * nullable cid and no foreign key, so it is truncated explicitly.
   */
  protected static void resetDatabase(DataSource dataSource) {
    JdbcClient.create(dataSource)
        .sql("TRUNCATE cid, audit_event RESTART IDENTITY CASCADE")
        .update();
  }
}
