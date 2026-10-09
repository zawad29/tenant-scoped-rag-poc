package com.example.ragpoc.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes users. */
@Repository
public class UserRepository {

  private final JdbcClient jdbc;

  public UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Finds a user by email for authentication.
   *
   * <p>Matching is case-insensitive to agree with the unique index on
   * {@code lower(email)}; otherwise a user could register a second account that
   * differs only in case.
   *
   * <p>This is the one lookup in the system that is deliberately not tenant
   * scoped, because it happens before any tenant is known: it is what
   * establishes the tenant. It cannot leak anything, since authentication
   * failure reveals nothing about which tenants exist.
   */
  public Optional<AppUser> findByEmail(String email) {
    return jdbc
        .sql(
            """
            SELECT id, tenant_id, email, password_hash, role, enabled
              FROM app_user
             WHERE lower(email) = lower(:email)
            """)
        .param("email", email)
        .query(UserRepository::mapRow)
        .optional();
  }

  /**
   * Tenant-scoped lookup: the pattern every other repository method must follow.
   *
   * <p>{@code AND tenant_id = :tenantId} is not an optimization, it is the
   * access check. A caller that supplies another tenant's id gets an empty
   * result, which callers surface as 404 rather than 403 so that the existence
   * of another tenant's records is not confirmed.
   */
  public Optional<AppUser> findByIdAndTenant(UUID id, UUID tenantId) {
    return jdbc
        .sql(
            """
            SELECT id, tenant_id, email, password_hash, role, enabled
              FROM app_user
             WHERE id = :id
               AND tenant_id = :tenantId
            """)
        .param("id", id)
        .param("tenantId", tenantId)
        .query(UserRepository::mapRow)
        .optional();
  }

  private static AppUser mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new AppUser(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("email"),
        rs.getString("password_hash"),
        parseRole(rs.getString("role")),
        rs.getBoolean("enabled"));
  }

  /**
   * Fails closed on an unrecognised role.
   *
   * <p>Falling back to {@code USER} would be safer than falling back to an
   * admin role, but silently continuing with a role the application does not
   * understand hides a migration or data problem behind a working login.
   */
  private static Role parseRole(String value) {
    try {
      return Role.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Unrecognised role in database: " + value, e);
    }
  }
}
