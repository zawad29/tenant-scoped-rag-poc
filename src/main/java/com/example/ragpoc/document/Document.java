package com.example.ragpoc.document;

import java.time.Instant;
import java.util.UUID;

/**
 * A document, as stored.
 *
 * @param contentHash hash of the active version, used to skip redundant
 *     re-ingestion
 * @param currentVersion the only version whose chunks are answerable; 0 before
 *     the first successful ingestion
 */
public record Document(
    UUID id,
    UUID tenantId,
    String title,
    String originalFilename,
    String contentHash,
    DocumentStatus status,
    int currentVersion,
    Integer pageCount,
    Instant createdAt,
    Instant updatedAt) {

  public boolean active() {
    return status == DocumentStatus.ACTIVE;
  }
}
