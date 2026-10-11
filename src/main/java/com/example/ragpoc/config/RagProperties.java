package com.example.ragpoc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable in one place: chunk sizing, candidate counts, thresholds and
 * connection settings. Thresholds are starting points, not conclusions.
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
    Embedding embedding,
    Rerank rerank,
    Chunking chunking,
    Retrieval retrieval,
    Guard guard,
    Qdrant qdrant,
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
      boolean enabled,
      String modelId,
      String modelPath,
      String tokenizerPath,
      int maxSequenceLength,
      int batchSize) {}

  public record Chunking(int targetTokens, int maxTokens, int minTokens, int overlapPercent) {}

  public record Retrieval(
      int denseCandidates,
      int rerankTopN,
      double minRelevance,
      double minChunkRelevance,
      int contextTokenBudget) {}

  public record Guard(int maxQuestionChars, int requestsPerMinutePerUser) {}

  /** Qdrant connection settings. */
  public record Qdrant(
      String host, int port, String apiKey, String collection, boolean useTls, int timeoutMs) {}

  public record Ingestion(long maxFileBytes, int maxPages) {}

  /** Where original files are kept. Local filesystem for the PoC. */
  public record Storage(String localRoot) {}
}
