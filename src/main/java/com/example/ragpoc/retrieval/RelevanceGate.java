package com.example.ragpoc.retrieval;

/**
 * Decides whether retrieved evidence is good enough to answer from.
 *
 * <p>This is the main defence against confident nonsense, and it deliberately
 * does not use vector similarity. Cosine scores are not comparable across
 * embedding models, nor across queries within one model: a question with no
 * answer in the corpus still has a nearest neighbour, and "nearest" says nothing
 * about whether it is close. The cross-encoder's score is a judgement of the
 * pair, so a threshold on it means something.
 *
 * <p>Thresholds are properties, calibrated against the golden set in the
 * evaluation step. The defaults here exist so the system runs; they are not
 * claims about the right values.
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

  /**
   * Outcome of the gate.
   *
   * @param answer false when the request must be refused without calling the model
   * @param reason recorded in the retrieval trace, so a refusal can be explained
   * @param bestScore the strongest reranker score seen
   */
  public record GateDecision(
      boolean answer, String reason, double bestScore, int keptCount, int droppedCount) {}

  /**
   * Evaluates reranker scores.
   *
   * @param rerankScores raw cross-encoder logits, in candidate order; empty when
   *     nothing was retrieved
   */
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
      // The evidence is too weak to answer from. Returning the refusal here,
      // rather than letting the model decide, is what makes "refuse instead of
      // guessing" a property of the system rather than of one prompt.
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
   * Degraded gate for the configuration where no reranker is available.
   *
   * <p>RRF scores are rank-based and have no fixed scale, so they cannot be
   * compared against a threshold calibrated on cross-encoder logits. Rather than
   * invent a conversion, the absolute gate is switched off and only the
   * existence of candidates is required — with the consequence stated plainly:
   * answers in this mode are more likely to be grounded in weakly relevant
   * passages, which is why the reranker is enabled by default.
   */
  public GateDecision evaluateWithoutReranker(int candidateCount) {
    if (candidateCount == 0) {
      return new GateDecision(false, "no candidates retrieved", Double.NEGATIVE_INFINITY, 0, 0);
    }
    return new GateDecision(
        true,
        "no reranker configured: RRF ordering only, absolute relevance gate disabled",
        Double.NaN,
        candidateCount,
        0);
  }

  /**
   * Maps a raw score to (0, 1) for display and logging.
   *
   * <p>Only presentation. The gate compares raw logits, because the sigmoid is
   * monotonic and applying it before thresholding would just hide the actual
   * quantity being compared.
   */
  public static double asRelevance(double rawScore) {
    return 1.0 / (1.0 + Math.exp(-rawScore));
  }

  public double minRelevance() {
    return minRelevance;
  }

  public double minChunkRelevance() {
    return minChunkRelevance;
  }
}
