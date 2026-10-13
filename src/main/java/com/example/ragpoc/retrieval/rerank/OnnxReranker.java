package com.example.ragpoc.retrieval.rerank;

import com.example.ragpoc.retrieval.onnx.OnnxCrossEncoder;
import ai.onnxruntime.OrtException;
import java.util.List;

/**
 * A {@link Reranker} backed by a local ONNX cross-encoder.
 *
 * <p>Business logic only: split the candidates into batches for throughput, run
 * each batch, and arraycopy the scores back so the result stays in input order.
 * The tensor arithmetic lives in the {@code onnx} helpers.
 *
 * <p>Raw logits are returned untouched ({@link ScoringScale#LOGIT}); the
 * relevance gate thresholds these exact values.
 */
public final class OnnxReranker implements Reranker {

  private final OnnxCrossEncoder encoder;
  private final String modelId;
  private final int batchSize;

  public OnnxReranker(OnnxCrossEncoder encoder, String modelId, int batchSize) {
    this.encoder = encoder;
    this.modelId = modelId;
    this.batchSize = Math.max(1, batchSize);
  }

  @Override
  public String modelId() {
    return modelId;
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public ScoringScale scale() {
    return ScoringScale.LOGIT;
  }

  @Override
  public double[] score(String query, List<String> passages) {
    double[] scores = new double[passages.size()];
    for (int start = 0; start < passages.size(); start += batchSize) {
      int end = Math.min(passages.size(), start + batchSize);
      double[] batch = scoreBatch(query, passages.subList(start, end));
      System.arraycopy(batch, 0, scores, start, batch.length);
    }
    return scores;
  }

  // The ONNX session and the tokenizer are not thread safe.
  private synchronized double[] scoreBatch(String query, List<String> passages) {
    try {
      return encoder.score(query, passages);
    } catch (OrtException e) {
      throw new IllegalStateException("ONNX reranking failed for model " + modelId, e);
    }
  }
}
