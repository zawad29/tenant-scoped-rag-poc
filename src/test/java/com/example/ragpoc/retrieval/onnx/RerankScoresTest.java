package com.example.ragpoc.retrieval.onnx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RerankScoresTest {

  @Test
  @DisplayName("takes the single label as the raw score")
  void takesTheOnlyLabel() {
    double[] scores = RerankScores.singleLogits(new float[][] {{4.5f}, {-2.0f}});
    assertThat(scores).containsExactly(4.5, -2.0);
  }

  @Test
  @DisplayName("rejects a graph that emits more than one label per pair")
  void rejectsMultipleLabels() {
    try {
      RerankScores.singleLogits(new float[][] {{1f, 2f}});
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageContaining("single relevance logit");
      return;
    }
    throw new AssertionError("expected an IllegalStateException");
  }
}
