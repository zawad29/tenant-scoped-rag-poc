package com.example.ragpoc.retrieval.rerank;

/**
 * The scale a {@link Reranker}'s scores are on.
 *
 * <p>A local cross-encoder emits raw logits; a hosted reranking API usually
 * returns a calibrated relevance in {@code [0, 1]}. The gate thresholds are only
 * meaningful within one scale, which is why they are configured per provider and
 * why the scale travels with the scores into the trace.
 */
public enum ScoringScale {
  /** Unbounded model logits, as emitted by the local ONNX cross-encoder. */
  LOGIT,

  /** A calibrated relevance score in {@code [0, 1]}, as hosted APIs return. */
  NORMALIZED
}
