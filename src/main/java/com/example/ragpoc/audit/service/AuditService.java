package com.example.ragpoc.audit.service;

import com.example.ragpoc.audit.entity.AuditEvent;
import com.example.ragpoc.audit.repository.AuditEventRepository;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Writes the audit trail.
 *
 * <p>The security events are the interesting ones: if the post-retrieval cid
 * assertion fires, the request is aborted <em>and</em> a row lands here, so a
 * filter bug leaves evidence.
 */
@Service
@Slf4j
public class AuditService {

  private final AuditEventRepository events;

  public AuditService(AuditEventRepository events) {
    this.events = events;
  }

  /**
   * Records an event. Never throws: an audit write failure must not turn a
   * completed upload into an error, nor mask the original failure it was being
   * recorded alongside.
   */
  public void record(UUID cidId, UUID userId, AuditEvent.Type type, String detail) {
    try {
      events.save(AuditEvent.of(cidId, userId, type, detail));
    } catch (RuntimeException e) {
      log.warn("Could not write audit event {}: {}", type, e.getMessage());
    }
  }
}
