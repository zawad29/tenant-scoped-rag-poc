package com.example.ragpoc.retrieval.onnx;

/**
 * Turns a cross-encoder output matrix into one raw score per pair.
 *
 * <p>The model must produce exactly one relevance label per pair; anything else
 * means the wrong graph was exported, which would otherwise show up as a
 * silently wrong ranking.
 */
public final class RerankScores {

  private RerankScores() {}

  public static double[] singleLogits(float[][] logits) {
    double[] scores = new double[logits.length];
    for (int i = 0; i < logits.length; i++) {
      if (logits[i].length != 1) {
        throw new IllegalStateException(
            "Expected a single relevance logit per pair but got " + logits[i].length);
      }
      scores[i] = logits[i][0];
    }
    return scores;
  }
}
