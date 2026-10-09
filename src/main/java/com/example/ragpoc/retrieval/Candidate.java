package com.example.ragpoc.retrieval;

import com.example.ragpoc.port.SearchHit;

/**
 * A fused candidate with every score that contributed to it.
 *
 * <p>All scores are kept rather than only the winning one, because the trace is
 * what makes a bad answer explainable: knowing that a passage ranked 2nd for
 * keywords but 40th for embeddings is the difference between "the reranker
 * disagreed" and "the embedding missed it".
 */
public record Candidate(
    SearchHit hit, Double denseScore, Double keywordScore, double rrfScore, double rerankScore) {

  public Candidate withRerankScore(double score) {
    return new Candidate(hit, denseScore, keywordScore, rrfScore, score);
  }
}
