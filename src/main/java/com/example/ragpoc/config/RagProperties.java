package com.example.ragpoc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable in one place: embedding and reranking providers, chunk sizing,
 * candidate counts, thresholds and connection settings.
 *
 * <p>Thresholds are starting points, not conclusions, and they are expressed in
 * the reranker's own score scale: swapping in a hosted reranker means changing
 * the provider block and recalibrating its thresholds there.
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

  /**
   * @param provider the embedding backend, {@code transformers} today and an
   *     external service later
   * @param modelId recorded in the retrieval trace and on each version
   * @param dimension the vector width; changing it requires re-indexing
   */
  public record Embedding(String provider, String modelId, int dimension, Transformers transformers) {}

  /** Local ONNX embedding settings (Spring AI's TransformersEmbeddingModel). */
  public record Transformers(
      String modelPath, String tokenizerPath, String outputName, int maxSequenceLength) {}

  /**
   * @param provider the reranking backend, {@code onnx} or {@code none}
   * @param topN how many chunks reach the context
   * @param failOpen when true, a reranker failure degrades to dense order
   */
  public record Rerank(String provider, int topN, boolean failOpen, Onnx onnx) {

    /**
     * Thresholds live with the provider because they are only meaningful in its
     * score scale: raw logits for the local cross-encoder.
     */
    public record Onnx(
        String modelPath,
        String tokenizerPath,
        int maxSequenceLength,
        int batchSize,
        double minRelevance,
        double minChunkRelevance) {}
  }

  public record Chunking(int targetTokens, int maxTokens, int minTokens, int overlapPercent) {}

  public record Retrieval(int denseCandidates, int contextTokenBudget) {}

  public record Guard(int maxQuestionChars, int requestsPerMinutePerUser) {}

  /** Qdrant connection settings. */
  public record Qdrant(
      String host, int port, String apiKey, String collection, boolean useTls, int timeoutMs) {}

  public record Ingestion(long maxFileBytes, int maxPages) {}

  /** Where original files are kept. Local filesystem for the PoC. */
  public record Storage(String localRoot) {}
}
