package com.example.ragpoc.audit.repository;

import com.example.ragpoc.audit.entity.AuditEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Audit trail persistence. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {}
