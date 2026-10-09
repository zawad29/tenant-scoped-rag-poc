package com.example.ragpoc.retrieval;

import java.util.UUID;

/**
 * A chunk selected for the prompt, with a stable citation identifier.
 *
 * @param sourceId the label the model must cite, {@code S1}, {@code S2}, ...
 * @param relevance {@code sigmoid(rerankScore)} for display; the gate itself
 *     compares raw scores
 */
public record RetrievedChunk(
    String sourceId,
    UUID chunkId,
    UUID documentId,
    int version,
    int pageStart,
    int pageEnd,
    String sectionTitle,
    String docTitle,
    String text,
    Double denseScore,
    Double keywordScore,
    double rrfScore,
    double rerankScore,
    double relevance) {

  /** Page range as shown in a citation. */
  public String pageRange() {
    return pageStart == pageEnd ? String.valueOf(pageStart) : pageStart + "-" + pageEnd;
  }
}
