package com.example.ragpoc.cid;

import com.example.ragpoc.user.entity.Role;
import java.util.Objects;
import java.util.UUID;

/**
 * Who is asking and on behalf of which cid.
 *
 * <p>A value object, never a {@code ThreadLocal}, and a parameter of every
 * knowledge-access method: the cid is constructed once from the authenticated
 * principal and then only passed along. Background work carries the cid on its
 * job row.
 *
 * @param cidId the cid whose data may be touched; never null
 * @param userId the acting user, or null for system-initiated work
 * @param role the acting role
 */
public record CidContext(UUID cidId, UUID userId, Role role) {

  public CidContext {
    Objects.requireNonNull(cidId, "cidId is mandatory: there is no cid-free access path");
  }

  public static CidContext of(UUID cidId, UUID userId, Role role) {
    return new CidContext(cidId, userId, role);
  }

  /** System-initiated work (ingestion jobs), with no acting user. */
  public static CidContext system(UUID cidId) {
    return new CidContext(cidId, null, Role.CID_ADMIN);
  }

  @Override
  public String toString() {
    return "CidContext[cid=" + cidId + ", user=" + userId + ", role=" + role + "]";
  }
}
