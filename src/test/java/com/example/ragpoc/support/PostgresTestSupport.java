package com.example.ragpoc.support;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for tests that need a real database.
 *
 * <p>Real PostgreSQL with pgvector, not H2 or an embedded substitute. The whole
 * point of the isolation suite is to prove things about the actual query
 * planner, the actual vector operators and the actual filter behaviour; a
 * substitute engine would prove nothing about any of them.
 *
 * <p>The container is started once per JVM and shared by every subclass, since
 * starting PostgreSQL per test class would dominate the build time.
 */
public abstract class PostgresTestSupport {

  /** Same image as docker-compose.yml, so tests and local runs agree. */
  public static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
              DockerImageName.parse("pgvector/pgvector:0.8.7-pg17")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ragpoc")
          .withUsername("ragpoc")
          .withPassword("ragpoc")
          .withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off");

  static {
    POSTGRES.start();
  }

  /**
   * Registers connection details, plus a placeholder API key.
   *
   * <p>Without the key the Anthropic autoconfiguration would fail on a machine
   * that has no DeepSeek credentials, making the suite depend on developer
   * environment variables. No test in this class hierarchy calls the model, so
   * the value is never used to make a request.
   */
  protected static void registerDatasourceProperties(
      org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.ai.anthropic.api-key", () -> "test-key-not-used");
  }

  // --- Seeding helpers ------------------------------------------------------

  /**
   * Inserts a tenant plus one user and returns the user's id.
   *
   * <p>Tests write rows directly rather than through services so that a bug in
   * a service cannot make an isolation test pass.
   */
  protected static UUID seedTenantWithUser(
      DataSource dataSource,
      PasswordEncoder passwordEncoder,
      String tenantName,
      String email,
      String rawPassword,
      String role) {

    JdbcClient jdbc = JdbcClient.create(dataSource);
    UUID tenantId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    jdbc.sql("INSERT INTO tenant (id, name, status) VALUES (:id, :name, 'ACTIVE')")
        .param("id", tenantId)
        .param("name", tenantName)
        .update();

    jdbc.sql(
            """
            INSERT INTO app_user (id, tenant_id, email, password_hash, role, enabled)
            VALUES (:id, :tenantId, :email, :hash, :role, true)
            """)
        .param("id", userId)
        .param("tenantId", tenantId)
        .param("email", email)
        .param("hash", passwordEncoder.encode(rawPassword))
        .param("role", role)
        .update();

    return userId;
  }

  protected static UUID seedTenant(DataSource dataSource, String tenantName) {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    UUID tenantId = UUID.randomUUID();
    jdbc.sql("INSERT INTO tenant (id, name, status) VALUES (:id, :name, 'ACTIVE')")
        .param("id", tenantId)
        .param("name", tenantName)
        .update();
    return tenantId;
  }

  protected static UUID tenantIdOf(DataSource dataSource, String email) {
    return JdbcClient.create(dataSource)
        .sql("SELECT tenant_id FROM app_user WHERE lower(email) = lower(:email)")
        .param("email", email)
        .query(UUID.class)
        .single();
  }

  /**
   * Empties every tenant-owned table between tests.
   *
   * <p>Truncating {@code tenant} cascades to everything with a foreign key to it,
   * so this stays correct as tables are added instead of needing a
   * hand-maintained list.
   *
   * <p>{@code chunk} is truncated explicitly because it has no foreign keys on
   * purpose: it belongs to the vector store, which in the Qdrant configuration is
   * a different system entirely with no referential link to the relational
   * schema. Leaving it out would leak chunks from one test into the next.
   */
  protected static void resetDatabase(DataSource dataSource) {
    JdbcClient.create(dataSource)
        .sql("TRUNCATE tenant, chunk RESTART IDENTITY CASCADE")
        .update();
  }
}
