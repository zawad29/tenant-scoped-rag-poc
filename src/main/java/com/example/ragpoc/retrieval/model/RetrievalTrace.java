package com.example.ragpoc.retrieval.model;

import java.util.List;
import java.util.UUID;

/**
 * What retrieval actually did, stored with the assistant message.
 *
 * <p>An answer that looks wrong can then be explained rather than guessed at:
 * which candidates the dense search produced, what the reranker scored them,
 * where the gate sat relative to its threshold, and how long each stage took.
 */
public record RetrievalTrace(
    String question,
    String decision,
    String reason,
    int denseCandidates,
    int rerankedCandidates,
    int selectedChunks,
    double bestRerankScore,
    double minRelevance,
    double minChunkRelevance,
    String embeddingModel,
    String rerankerModel,
    long embedMillis,
    long searchMillis,
    long rerankMillis,
    long assembleMillis,
    List<CandidateTrace> candidates,
    List<Citation> citations) {

  /**
   * Per-candidate detail, in dense order.
   *
   * <p>{@code text} is carried so the chat panel can show the content of every
   * considered candidate, not only the selected ones. It is by far the largest
   * field here; if the trace is ever persisted per message, this is the one to
   * trim.
   */
  public record CandidateTrace(
      UUID chunkId,
      UUID documentId,
      String docTitle,
      double denseScore,
      Double rerankScore,
      int denseRank,
      Integer rerankRank,
      boolean selected,
      String droppedReason,
      String text) {}

  /** What was shown to the user as a source. */
  public record Citation(
      String sourceId,
      UUID documentId,
      int version,
      String docTitle,
      String sectionTitle) {}
}
