package com.example.ragpoc.retrieval.service;

import ai.onnxruntime.OrtException;
import com.example.ragpoc.retrieval.onnx.OnnxTextModel;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process ONNX cross-encoder reranker.
 *
 * <p>The query and each candidate passage are scored together by one model pass,
 * which is why this beats embedding similarity at judging relevance. Raw logits
 * are returned unchanged; the relevance gate thresholds these exact values.
 */
public class RerankerService {

  private final OnnxTextModel model;
  private final String modelId;
  private final int batchSize;

  public RerankerService(OnnxTextModel model, String modelId, int batchSize) {
    this.model = model;
    this.modelId = modelId;
    this.batchSize = Math.max(1, batchSize);
  }

  public String modelId() {
    return modelId;
  }

  public double[] score(String query, List<String> passages) {
    double[] scores = new double[passages.size()];
    for (int start = 0; start < passages.size(); start += batchSize) {
      int end = Math.min(passages.size(), start + batchSize);
      double[] batchScores = scoreBatch(query, passages.subList(start, end));
      System.arraycopy(batchScores, 0, scores, start, batchScores.length);
    }
    return scores;
  }

  private synchronized double[] scoreBatch(String query, List<String> passages) {
    List<String[]> pairs = new ArrayList<>(passages.size());
    for (String passage : passages) {
      pairs.add(new String[] {query, passage});
    }

    float[][] logits;
    try {
      logits = model.forwardPairs(pairs);
    } catch (OrtException e) {
      throw new IllegalStateException("ONNX reranking failed for model " + modelId, e);
    }

    double[] scores = new double[logits.length];
    for (int i = 0; i < logits.length; i++) {
      if (logits[i].length != 1) {
        throw new IllegalStateException(
            "Expected a single relevance logit per pair but got " + logits[i].length);
      }
      scores[i] = logits[i][0];
    }
    return scores;
  }
}
