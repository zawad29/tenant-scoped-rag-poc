package com.example.ragpoc.adapter.rerank;

import ai.onnxruntime.OrtException;
import com.example.ragpoc.adapter.onnx.OnnxTextModel;
import com.example.ragpoc.port.RerankerPort;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process ONNX cross-encoder reranker.
 *
 * <p>The query and each candidate passage are scored together by one model
 * pass, which is why this beats embedding similarity at judging relevance and
 * why the relevance gate can trust its output. The cost is proportional to the
 * number of candidates, so it only ever sees the fused shortlist.
 *
 * <p>Raw logits are returned unchanged; turning them into something
 * interpretable is the relevance gate's job, and its calibration depends on
 * these exact values.
 */
public final class OnnxCrossEncoderRerankerAdapter implements RerankerPort {

  private final OnnxTextModel model;
  private final String modelId;
  private final int batchSize;

  public OnnxCrossEncoderRerankerAdapter(OnnxTextModel model, String modelId, int batchSize) {
    this.model = model;
    this.modelId = modelId;
    this.batchSize = Math.max(1, batchSize);
  }

  @Override
  public String modelId() {
    return modelId;
  }

  @Override
  public double[] score(String query, List<String> passages) {
    double[] scores = new double[passages.size()];
    for (int start = 0; start < passages.size(); start += batchSize) {
      int end = Math.min(passages.size(), start + batchSize);
      double[] batchScores = scoreBatch(query, passages.subList(start, end));
      System.arraycopy(batchScores, 0, scores, start, batchScores.length);
    }
    return scores;
  }

  // One shared ONNX session: serialize the passes.
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
