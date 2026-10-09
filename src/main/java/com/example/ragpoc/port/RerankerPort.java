package com.example.ragpoc.port;

import java.util.List;

/**
 * Scores how relevant a passage is to a query.
 *
 * <p>A cross-encoder reads the query and passage together, which is far more
 * accurate than comparing two independent embeddings. It is also far more
 * expensive, so it is applied only to a small fused candidate set.
 *
 * <p>Scores are raw model outputs and are <em>not</em> probabilities and
 * <em>not</em> comparable to cosine similarity. The relevance gate calibrates
 * against these values, which is why they are returned unmodified.
 */
public interface RerankerPort {

  /** Stable identifier of the model, recorded in the retrieval trace. */
  String modelId();

  /**
   * Scores each passage against the query.
   *
   * @return one raw score per passage, in input order; higher means more
   *     relevant
   */
  double[] score(String query, List<String> passages);
}
