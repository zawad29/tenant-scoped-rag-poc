package com.example.ragpoc.retrieval.math;

/**
 * Small, pure numeric helpers shared by the retrieval pipeline.
 *
 * <p>Stateless and free of any ONNX or Spring AI types, so the arithmetic can be
 * unit tested without loading a model.
 */
public final class VectorMath {

  private VectorMath() {}

  /** Squashes a raw score into {@code (0, 1)}. Used for display only. */
  public static double sigmoid(double x) {
    return 1.0 / (1.0 + Math.exp(-x));
  }

  /** Euclidean length of a vector. */
  public static double magnitude(float[] vector) {
    double sum = 0;
    for (float v : vector) {
      sum += (double) v * v;
    }
    return Math.sqrt(sum);
  }

  /** Cosine similarity of two vectors; 0 when either has zero length. */
  public static double cosine(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException(
          "vectors must have the same length: " + a.length + " vs " + b.length);
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /** Scales a vector to unit length in place; leaves a zero vector untouched. */
  public static void l2NormalizeInPlace(float[] vector) {
    double norm = magnitude(vector);
    if (norm == 0) {
      return;
    }
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) (vector[i] / norm);
    }
  }
}
