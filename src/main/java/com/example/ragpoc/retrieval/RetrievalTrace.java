package com.example.ragpoc.retrieval;

import java.util.List;
import java.util.UUID;

/**
 * What retrieval actually did, stored with the assistant message.
 *
 * <p>An answer that looks wrong can then be explained rather than guessed at:
 * which candidates each retriever produced, what the reranker scored them, where
 * the gate sat relative to its threshold, and how long each stage took. This is
 * also the input the evaluation harness reads when calibrating thresholds.
 */
public record RetrievalTrace(
    String question,
    String decision,
    String reason,
    int denseCandidates,
    int keywordCandidates,
    int fusedCandidates,
    int rerankedCandidates,
    int selectedChunks,
    double bestRerankScore,
    double minRelevance,
    double minChunkRelevance,
    String embeddingModel,
    String rerankerModel,
    long embedMillis,
    long denseMillis,
    long keywordMillis,
    long rerankMillis,
    List<CandidateTrace> candidates,
    List<Citation> citations) {

  /** Per-candidate detail, in fused order. */
  public record CandidateTrace(
      UUID chunkId,
      UUID documentId,
      String docTitle,
      int pageStart,
      int pageEnd,
      Double denseRank,
      Double keywordRank,
      double rrfScore,
      Double rerankScore,
      boolean selected,
      String droppedReason) {}

  /** What was shown to the user as a source. */
  public record Citation(
      String sourceId, UUID documentId, int version, String docTitle, String sectionTitle,
      int pageStart, int pageEnd) {}
}
