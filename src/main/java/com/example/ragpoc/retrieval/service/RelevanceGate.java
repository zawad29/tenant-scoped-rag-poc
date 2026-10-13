package com.example.ragpoc.retrieval.service;

import com.example.ragpoc.retrieval.math.VectorMath;
import com.example.ragpoc.retrieval.rerank.ScoringScale;

/**
 * Decides whether retrieved evidence is good enough to answer from.
 *
 * <p>This is the main defence against confident nonsense, and it compares the
 * reranker's logits, not vector similarity: cosine scores are not comparable
 * across embedding models or across queries, and "nearest" says nothing about
 * whether a passage is close.
 */
public class RelevanceGate {

  private final double minRelevance;
  private final double minChunkRelevance;

  public RelevanceGate(double minRelevance, double minChunkRelevance) {
    if (minChunkRelevance > minRelevance) {
      throw new IllegalArgumentException(
          "minChunkRelevance must not exceed minRelevance: the per-chunk floor exists to drop "
              + "weak context from an otherwise answerable question, not to be the primary gate");
    }
    this.minRelevance = minRelevance;
    this.minChunkRelevance = minChunkRelevance;
  }

  public record GateDecision(
      boolean answer, String reason, double bestScore, int keptCount, int droppedCount) {}

  public GateDecision evaluate(double[] rerankScores) {
    if (rerankScores == null || rerankScores.length == 0) {
      return new GateDecision(false, "no candidates retrieved", Double.NEGATIVE_INFINITY, 0, 0);
    }

    double best = Double.NEGATIVE_INFINITY;
    int kept = 0;
    for (double score : rerankScores) {
      best = Math.max(best, score);
      if (score >= minChunkRelevance) {
        kept++;
      }
    }

    if (best < minRelevance) {
      return new GateDecision(
          false,
          "best reranker score %.3f is below min-relevance %.3f".formatted(best, minRelevance),
          best,
          kept,
          rerankScores.length - kept);
    }

    if (kept == 0) {
      return new GateDecision(
          false,
          "no individual chunk reached min-chunk-relevance %.3f".formatted(minChunkRelevance),
          best,
          0,
          rerankScores.length);
    }

    return new GateDecision(
        true,
        "best reranker score %.3f on %d of %d chunks".formatted(best, kept, rerankScores.length),
        best,
        kept,
        rerankScores.length - kept);
  }

  /**
   * Degraded gate for the configuration where no reranker is available: the
   * absolute gate is switched off and only the existence of candidates matters.
   */
  public GateDecision evaluateWithoutReranker(int candidateCount) {
    if (candidateCount == 0) {
      return new GateDecision(false, "no candidates retrieved", Double.NEGATIVE_INFINITY, 0, 0);
    }
    return new GateDecision(
        true,
        "no reranker configured: dense ordering only, absolute relevance gate disabled",
        Double.NaN,
        candidateCount,
        0);
  }

  /** Maps a raw score to (0, 1) for display only. */
  public static double asRelevance(double rawScore) {
    return VectorMath.sigmoid(rawScore);
  }

  /**
   * Maps a provider score to a bounded display value. A logit is squashed; a
   * score that is already normalised is only clamped.
   */
  public static double relevanceFor(ScoringScale scale, double score) {
    if (scale == ScoringScale.LOGIT) {
      return VectorMath.sigmoid(score);
    }
    return Math.max(0.0, Math.min(1.0, score));
  }

  public double minRelevance() {
    return minRelevance;
  }

  public double minChunkRelevance() {
    return minChunkRelevance;
  }
}
