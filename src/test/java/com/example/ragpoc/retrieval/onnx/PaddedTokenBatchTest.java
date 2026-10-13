package com.example.ragpoc.retrieval.onnx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaddedTokenBatchTest {

  @Test
  @DisplayName("right-pads shorter sequences to the longest in the batch")
  void padsToLongest() {
    long[][] ids = {{1, 2, 3}, {4, 5}};
    long[][] masks = {{1, 1, 1}, {1, 1}};
    long[][] types = {{0, 0, 0}, {0, 0}};

    PaddedTokenBatch batch = PaddedTokenBatch.pack(ids, masks, types);

    assertThat(batch.batch()).isEqualTo(2);
    assertThat(batch.maxLength()).isEqualTo(3);
    assertThat(batch.inputIds()).containsExactly(1, 2, 3, 4, 5, 0);
    assertThat(batch.attentionMask()).containsExactly(1, 1, 1, 1, 1, 0);
  }

  @Test
  @DisplayName("ignores token type ids for models that do not use them")
  void handlesMissingTypeIds() {
    long[][] ids = {{1, 2}, {3}};
    long[][] masks = {{1, 1}, {1}};
    long[][] types = {{}, {}};

    PaddedTokenBatch batch = PaddedTokenBatch.pack(ids, masks, types);

    assertThat(batch.tokenTypeIds()).containsExactly(0, 0, 0, 0);
  }

  @Test
  @DisplayName("rejects buffers that are not batch * maxLength")
  void rejectsInconsistentBuffers() {
    try {
      new PaddedTokenBatch(new long[3], new long[2], new long[2], 2, 2);
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageContaining("buffer length");
      return;
    }
    throw new AssertionError("expected an IllegalArgumentException");
  }
}
