package com.example.ragpoc.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads tenants. */
@Repository
public class TenantRepository {

  private final JdbcClient jdbc;

  public TenantRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Reads the calling tenant's own row.
   *
   * <p>The parameter is named {@code tenantId} rather than {@code id} because
   * there is no general-purpose finder here on purpose. The only tenant a caller
   * may read is its own, so no method exists that could be pointed at another
   * one, and the id always comes from the authenticated principal.
   */
  public Optional<Tenant> findOwn(UUID tenantId) {
    return jdbc
        .sql("SELECT id, name, status FROM tenant WHERE id = :tenantId")
        .param("tenantId", tenantId)
        .query(
            (rs, rowNum) ->
                new Tenant(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status")))
        .optional();
  }
}
