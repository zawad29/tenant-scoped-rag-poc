package com.example.ragpoc.retrieval.model;

import java.util.UUID;

/**
 * A chunk on its way into the vector store.
 *
 * <p>Note what is missing: there is no {@code cidId}. The cid comes from the
 * {@link com.example.ragpoc.cid.CidContext} passed alongside, so content and cid
 * are supplied through separate channels on purpose.
 *
 * @param chunkId deterministic identifier, derived from
 *     {@code cid:document:version:index} so retries are idempotent
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
