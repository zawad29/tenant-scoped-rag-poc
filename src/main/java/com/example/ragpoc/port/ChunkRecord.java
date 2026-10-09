package com.example.ragpoc.port;

import java.util.UUID;

/**
 * A chunk on its way into the index.
 *
 * <p>Note what is missing: there is no {@code tenantId}. The tenant comes from
 * the {@link com.example.ragpoc.tenant.TenantContext} passed alongside, so a
 * caller cannot index content under a tenant other than the one it is acting
 * for. Content and tenant are supplied through separate channels on purpose.
 *
 * @param chunkId deterministic identifier, derived from
 *     {@code tenant:document:version:index} so that retries are idempotent
 * @param version the document version this chunk belongs to; queries only ever
 *     match the active version
 * @param text the clean display text, without the contextual prefix that was
 *     embedded
 * @param embeddingModel recorded so that vectors from different models are
 *     never mixed in one index
 */
public record ChunkRecord(
    UUID chunkId,
    UUID documentId,
    int version,
    int chunkIndex,
    int pageStart,
    int pageEnd,
    String sectionTitle,
    String docTitle,
    String text,
    String embeddingModel,
    float[] embedding) {

  public ChunkRecord {
    if (docTitle == null || docTitle.isBlank()) {
      throw new IllegalArgumentException("docTitle is required for citation rendering");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("chunk text must not be blank");
    }
    if (pageStart > pageEnd) {
      throw new IllegalArgumentException("pageStart must not exceed pageEnd");
    }
  }
}
