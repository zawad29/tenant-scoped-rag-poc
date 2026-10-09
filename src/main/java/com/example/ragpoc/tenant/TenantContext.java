package com.example.ragpoc.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is asking, and on behalf of which tenant.
 *
 * <p>This is a plain value object, never a {@code ThreadLocal}, and it is a
 * required parameter of every knowledge-access method. Two properties follow
 * from that:
 *
 * <ul>
 *   <li>The tenant cannot be supplied by a request body, query string, header
 *       or model output — it is constructed once, from the authenticated
 *       principal, and then only passed along.
 *   <li>Cross-thread bleed is impossible to introduce by accident, because
 *       there is no ambient state to inherit. Async ingestion carries the
 *       tenant on its job row instead.
 * </ul>
 *
 * @param tenantId the tenant whose data may be touched; never null
 * @param userId the acting user, or null for system-initiated work
 * @param role the acting role
 */
public record TenantContext(UUID tenantId, UUID userId, Role role) {

  public TenantContext {
    Objects.requireNonNull(tenantId, "tenantId is mandatory: there is no tenant-free access path");
  }

  public static TenantContext of(UUID tenantId, UUID userId, Role role) {
    return new TenantContext(tenantId, userId, role);
  }

  /** System-initiated work (ingestion jobs), with no acting user. */
  public static TenantContext system(UUID tenantId) {
    return new TenantContext(tenantId, null, Role.TENANT_ADMIN);
  }

  @Override
  public String toString() {
    // Deliberately omits nothing tenant-identifying, but stays terse so it can
    // appear in logs.
    return "TenantContext[tenant=" + tenantId + ", user=" + userId + ", role=" + role + "]";
  }
}
