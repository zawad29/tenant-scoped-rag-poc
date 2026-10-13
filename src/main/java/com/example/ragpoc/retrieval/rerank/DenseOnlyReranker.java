package com.example.ragpoc.retrieval.rerank;

import java.util.List;

/**
 * The reranker used when none is configured, or when an external one has failed
 * and the pipeline is set to fail open.
 *
 * <p>It returns a zero score for every passage, so ordering falls back to the
 * dense similarity ranking and the absolute relevance gate is disabled. Having a
 * real object rather than a {@code null} check keeps the retrieval service free
 * of provider-specific branches.
 */
public final class DenseOnlyReranker implements Reranker {

  @Override
  public String modelId() {
    return "none";
  }

  @Override
  public boolean enabled() {
    return false;
  }

  @Override
  public ScoringScale scale() {
    return ScoringScale.NORMALIZED;
  }

  @Override
  public double[] score(String query, List<String> passages) {
    return new double[passages.size()];
  }
}
