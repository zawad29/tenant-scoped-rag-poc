package com.example.ragpoc.audit;

import com.example.ragpoc.tenant.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Writes the audit trail.
 *
 * <p>The security events are the interesting ones. If the post-retrieval tenant
 * assertion ever fires, the request is aborted *and* a row lands here, so a
 * filter bug leaves evidence rather than quietly serving the wrong tenant's
 * text and leaving nobody the wiser.
 */
@Service
public class AuditService {

  /** Event types, mirrored by a check constraint on the table. */
  public enum Type {
    DOCUMENT_UPLOADED,
    DOCUMENT_REPLACED,
    DOCUMENT_DELETED,
    INGESTION_FAILED,
    CHAT_SESSION_DELETED,
    SECURITY_TENANT_MISMATCH,
    SECURITY_ACCESS_DENIED,
    LOGIN_FAILED
  }

  private final JdbcClient jdbc;

  public AuditService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(TenantContext tenant, Type type, String detail) {
    record(tenant == null ? null : tenant.tenantId(), tenant == null ? null : tenant.userId(), type, detail);
  }

  /**
   * Records an event.
   *
   * <p>Never throws. An audit write failure must not turn a completed upload
   * into an error for the user, and must not mask the original failure it was
   * being recorded alongside.
   */
  public void record(UUID tenantId, UUID userId, Type type, String detail) {
    try {
      jdbc.sql(
              """
              INSERT INTO audit_event (id, tenant_id, user_id, type, detail)
              VALUES (:id, :tenantId, :userId, :type, :detail)
              """)
          .param("id", UUID.randomUUID())
          .param("tenantId", tenantId)
          .param("userId", userId)
          .param("type", type.name())
          .param("detail", detail)
          .update();
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(AuditService.class)
          .warn("Could not write audit event {}: {}", type, e.getMessage());
    }
  }
}
