package com.example.ragpoc.tenant;

/**
 * Roles recognized by the application (specification O3).
 *
 * <p>Every user belongs to exactly one tenant, so there is no tenant-free role.
 * A platform-level administrator screen is explicitly deferred; when it arrives
 * it will need a deliberate answer to "which tenant does this account act for",
 * and that answer must not be "any".
 */
public enum Role {
  /** Uploads, replaces and deletes documents, inside their own tenant only. */
  TENANT_ADMIN,

  /** Chats with their own tenant's documents. */
  USER;

  public String authority() {
    return "ROLE_" + name();
  }
}
