package com.example.ragpoc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable in one place.
 *
 * <p>These values are the ones the specification calls starting points rather
 * than conclusions: chunk sizing, candidate counts, and the relevance
 * thresholds. Keeping them together, and recording them in each retrieval
 * trace, means a surprising answer can always be explained by the configuration
 * that produced it.
 *
 * <p>Model paths are plain filesystem paths, not URIs: the models are local
 * files and the application never fetches them.
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
    Embedding embedding,
    Rerank rerank,
    Chunking chunking,
    Retrieval retrieval,
    Guard guard,
    Vector vector,
    Ingestion ingestion,
    Storage storage) {

  public record Embedding(
      String modelId,
      String modelPath,
      String tokenizerPath,
      String pooling,
      boolean normalize,
      int dimension,
      int maxSequenceLength,
      int batchSize) {}

  public record Rerank(
      String modelId, String modelPath, String tokenizerPath, int maxSequenceLength, int batchSize) {}

  public record Chunking(
      int targetTokens, int maxTokens, int minTokens, int overlapPercent) {}

  public record Retrieval(
      int denseCandidates,
      int keywordCandidates,
      int fusedCandidates,
      int rerankTopN,
      int rrfK,
      double minRelevance,
      double minChunkRelevance,
      int contextTokenBudget,
      int historyTurnsForRewrite) {}

  public record Guard(int maxQuestionChars, int requestsPerMinutePerUser) {}

  /** Which vector store adapter is active. */
  public record Vector(String provider) {}

  public record Ingestion(
      long maxFileBytes,
      int maxPages,
      int workerThreads,
      int maxAttempts,
      long pollIntervalMs,
      boolean workerEnabled) {}

  /** Where original files are kept. Local filesystem for the PoC. */
  public record Storage(String localRoot) {}
}
