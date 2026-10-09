package com.example.ragpoc.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the locally fetched ONNX models.
 *
 * <p>The models are large and gitignored, so tests that need them are skipped
 * with an explicit assumption rather than failing when
 * {@code scripts/fetch-models.sh} has not been run.
 */
public final class ModelAssets {

  private static final Path MODELS_DIR =
      Paths.get(System.getProperty("rag.models.dir", "models")).toAbsolutePath();

  private ModelAssets() {}

  public static Path embeddingModel() {
    return MODELS_DIR.resolve("bge-base-en-v1.5/model.onnx");
  }

  public static Path embeddingTokenizer() {
    return MODELS_DIR.resolve("bge-base-en-v1.5/tokenizer.json");
  }

  public static Path rerankerModel() {
    return MODELS_DIR.resolve("ms-marco-MiniLM-L-6-v2/model.onnx");
  }

  public static Path rerankerTokenizer() {
    return MODELS_DIR.resolve("ms-marco-MiniLM-L-6-v2/tokenizer.json");
  }

  public static boolean available() {
    return Files.isReadable(embeddingModel())
        && Files.isReadable(embeddingTokenizer())
        && Files.isReadable(rerankerModel())
        && Files.isReadable(rerankerTokenizer());
  }

  /** Message used as the JUnit assumption reason. */
  public static String missingMessage() {
    return "Local ONNX models are absent; run scripts/fetch-models.sh (looked in "
        + MODELS_DIR
        + ")";
  }

  // --- Test helpers ---------------------------------------------------------

  public static double cosine(float[] a, float[] b) {
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

  public static double magnitude(float[] a) {
    double sum = 0;
    for (float v : a) {
      sum += (double) v * v;
    }
    return Math.sqrt(sum);
  }
}
