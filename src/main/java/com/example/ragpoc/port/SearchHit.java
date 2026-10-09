package com.example.ragpoc.port;

import java.util.UUID;

/**
 * A chunk returned from the index.
 *
 * <p>{@code tenantId} is carried back deliberately: it is what the store
 * actually recorded, independently of what was asked for. RetrievalService
 * compares it against the caller's tenant and aborts the request on any
 * mismatch. That check is the backstop for a filter-construction bug, which is
 * the main risk of metadata-level isolation.
 *
 * @param score the raw score from the originating search; never compared across
 *     sources, since dense and keyword scores are not commensurable
 */
public record SearchHit(
    UUID chunkId,
    UUID tenantId,
    UUID documentId,
    int version,
    int chunkIndex,
    int pageStart,
    int pageEnd,
    String sectionTitle,
    String docTitle,
    String text,
    double score,
    Source source) {

  public enum Source {
    DENSE,
    KEYWORD
  }

  /** Page range as shown in a citation, e.g. {@code "12"} or {@code "12-14"}. */
  public String pageRange() {
    return pageStart == pageEnd ? String.valueOf(pageStart) : pageStart + "-" + pageEnd;
  }
}
