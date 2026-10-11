package com.example.ragpoc.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One audit trail event. */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

  /** Event types, mirrored by a check constraint on the table. */
  public enum Type {
    DOCUMENT_UPLOADED,
    DOCUMENT_REPLACED,
    DOCUMENT_DELETED,
    INGESTION_FAILED,
    SECURITY_CID_MISMATCH,
    SECURITY_ACCESS_DENIED,
    LOGIN_FAILED
  }

  @Id private UUID id;

  // Nullable: some events (a failed login) happen before a cid is known.
  @Column(name = "cid_id")
  private UUID cidId;

  @Column(name = "user_id")
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Type type;

  private String detail;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected AuditEvent() {}

  public static AuditEvent of(UUID cidId, UUID userId, Type type, String detail) {
    AuditEvent event = new AuditEvent();
    event.id = UUID.randomUUID();
    event.cidId = cidId;
    event.userId = userId;
    event.type = type;
    event.detail = detail;
    return event;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCidId() {
    return cidId;
  }

  public UUID getUserId() {
    return userId;
  }

  public Type getType() {
    return type;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
