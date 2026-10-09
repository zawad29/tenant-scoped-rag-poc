package com.example.ragpoc.adapter.embed;

import com.example.ragpoc.adapter.onnx.OnnxTextModel;
import com.example.ragpoc.adapter.onnx.PoolingMode;
import com.example.ragpoc.port.EmbeddingPort;
import ai.onnxruntime.OrtException;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process ONNX embedding adapter.
 *
 * <p>Runs entirely inside the JVM against local model files: no API key, no
 * outbound network traffic, no separate service. This is the "no data egress"
 * path the specification calls for, and it is the default configuration.
 *
 * <p>The pooling mode is a required constructor argument rather than a default,
 * because getting it wrong degrades retrieval silently instead of failing.
 */
public final class OnnxEmbeddingAdapter implements EmbeddingPort {

  private final OnnxTextModel model;
  private final PoolingMode poolingMode;
  private final boolean normalize;
  private final String modelId;
  private final int dimension;
  private final int batchSize;

  public OnnxEmbeddingAdapter(
      OnnxTextModel model,
      String modelId,
      PoolingMode poolingMode,
      boolean normalize,
      int dimension,
      int batchSize) {
    this.model = model;
    this.modelId = modelId;
    this.poolingMode = poolingMode;
    this.normalize = normalize;
    this.dimension = dimension;
    this.batchSize = Math.max(1, batchSize);
  }

  @Override
  public String modelId() {
    return modelId;
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public float[] embed(String text) {
    return embedAll(List.of(text)).getFirst();
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    if (texts.isEmpty()) {
      return List.of();
    }
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (int start = 0; start < texts.size(); start += batchSize) {
      int end = Math.min(texts.size(), start + batchSize);
      vectors.addAll(embedBatch(texts.subList(start, end)));
    }
    return vectors;
  }

  // The ONNX session and the DJL tokenizer are not thread safe, and the
  // ingestion worker and request threads both call this adapter.
  private synchronized List<float[]> embedBatch(List<String> batch) {
    OnnxTextModel.SequenceBatch forward;
    try {
      forward = model.forwardSequences(batch);
    } catch (OrtException e) {
      throw new IllegalStateException("ONNX embedding failed for model " + modelId, e);
    }

    float[][][] hiddenStates = forward.hiddenStates();
    int[] lengths = forward.lengths();

    List<float[]> vectors = new ArrayList<>(hiddenStates.length);
    for (int i = 0; i < hiddenStates.length; i++) {
      float[] pooled = pool(hiddenStates[i], lengths[i]);
      if (normalize) {
        l2Normalize(pooled);
      }
      if (pooled.length != dimension) {
        throw new IllegalStateException(
            "Embedding model returned "
                + pooled.length
                + " dimensions but "
                + dimension
                + " were configured. Vectors of different sizes must never share an index.");
      }
      vectors.add(pooled);
    }
    return vectors;
  }

  private float[] pool(float[][] tokens, int length) {
    return switch (poolingMode) {
      case CLS -> tokens[0].clone();
      case MEAN -> {
        int hidden = tokens[0].length;
        float[] mean = new float[hidden];
        // Only real tokens: rows beyond `length` are batch padding and would
        // drag the vector toward the pad token's embedding.
        for (int t = 0; t < length; t++) {
          for (int h = 0; h < hidden; h++) {
            mean[h] += tokens[t][h];
          }
        }
        for (int h = 0; h < hidden; h++) {
          mean[h] /= length;
        }
        yield mean;
      }
    };
  }

  private static void l2Normalize(float[] vector) {
    double sum = 0;
    for (float v : vector) {
      sum += (double) v * v;
    }
    double norm = Math.sqrt(sum);
    if (norm == 0) {
      return;
    }
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) (vector[i] / norm);
    }
  }
}
