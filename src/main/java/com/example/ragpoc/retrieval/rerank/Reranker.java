package com.example.ragpoc.retrieval.rerank;

import java.util.List;

/**
 * Scores passages against a query and returns them in input order.
 *
 * <p>The provider-neutral seam of the reranking stage. Today the only scored
 * implementation is the local ONNX cross-encoder ({@link OnnxReranker}); an
 * external reranking service or Spring AI's future {@code RerankModel} can be
 * dropped in behind this interface without touching the post-processor, the gate
 * or the trace.
 *
 * <p>Implementations must be blocking, return one score per passage in the same
 * order as the input, and report their {@link ScoringScale}.
 */
public interface Reranker {

  /** Identifier recorded in the retrieval trace. */
  String modelId();

  /** Whether this reranker actually produces scores. */
  boolean enabled();

  /** The scale of the numbers returned by {@link #score}. */
  ScoringScale scale();

  /**
   * Scores each passage against the query.
   *
   * <p>Higher is more relevant. The values are the provider's own scores,
   * unmodified, because the relevance gate compares them directly.
   */
  double[] score(String query, List<String> passages);
}
