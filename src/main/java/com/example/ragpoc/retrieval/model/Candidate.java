package com.example.ragpoc.retrieval.model;

/** A dense-retrieved candidate with the reranker's score for it. */
public record Candidate(SearchHit hit, double rerankScore) {}
