package com.example.ragpoc.document;

import java.time.Instant;
import java.util.UUID;

/**
 * One ingested version of a document.
 *
 * <p>The embedding model and its dimension are recorded per version, because the
 * only safe way to change embedding model is to rebuild into a new index and
 * switch over. Recording them is what makes that migration auditable later.
 */
public record DocumentVersion(
    UUID id,
    UUID documentId,
    UUID tenantId,
    int version,
    String storageKey,
    String contentHash,
    String embeddingModel,
    Integer embeddingDim,
    int chunkCount,
    Instant createdAt) {}
