package com.example.ragpoc.retrieval.math;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorMathTest {

  @Test
  @DisplayName("sigmoid is 0.5 at zero and monotonic")
  void sigmoid() {
    assertThat(VectorMath.sigmoid(0)).isEqualTo(0.5);
    assertThat(VectorMath.sigmoid(10)).isGreaterThan(0.99);
    assertThat(VectorMath.sigmoid(-10)).isLessThan(0.01);
    assertThat(VectorMath.sigmoid(1)).isGreaterThan(VectorMath.sigmoid(0));
  }

  @Test
  @DisplayName("normalising produces unit length")
  void normalise() {
    float[] vector = {3f, 4f};
    VectorMath.l2NormalizeInPlace(vector);
    assertThat(VectorMath.magnitude(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(vector[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-6f));
    assertThat(vector[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-6f));
  }

  @Test
  @DisplayName("a zero vector is left untouched rather than divided by zero")
  void zeroVectorIsSafe() {
    float[] vector = {0f, 0f};
    VectorMath.l2NormalizeInPlace(vector);
    assertThat(vector).containsExactly(0f, 0f);
    assertThat(VectorMath.magnitude(vector)).isZero();
  }

  @Test
  @DisplayName("cosine of identical vectors is 1 and of orthogonal vectors is 0")
  void cosine() {
    assertThat(VectorMath.cosine(new float[] {1f, 0f}, new float[] {1f, 0f})).isEqualTo(1.0);
    assertThat(VectorMath.cosine(new float[] {1f, 0f}, new float[] {0f, 1f})).isEqualTo(0.0);
  }
}
