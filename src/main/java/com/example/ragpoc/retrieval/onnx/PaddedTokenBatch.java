package com.example.ragpoc.retrieval.onnx;

import ai.djl.huggingface.tokenizers.Encoding;

/**
 * A batch of tokenizer encodings padded into rectangular buffers.
 *
 * <p>ONNX Runtime wants one dense tensor per input, so variable-length sequences
 * must be right-padded to the longest sequence in the batch. Padding tokens get
 * id {@code 0} and an attention mask of {@code 0}, which keeps them out of the
 * model's attention. Token type ids are only meaningful for models that declare
 * that input; for the rest the buffer stays zeroed.
 *
 * <p>A pure data holder: constructing it touches no native code, so the packing
 * contract is directly unit-testable.
 */
public record PaddedTokenBatch(
    long[] inputIds, long[] attentionMask, long[] tokenTypeIds, int batch, int maxLength) {

  public PaddedTokenBatch {
    int expected = batch * maxLength;
    if (inputIds.length != expected
        || attentionMask.length != expected
        || tokenTypeIds.length != expected) {
      throw new IllegalArgumentException(
          "buffer length must be batch * maxLength ("
              + expected
              + "), got "
              + inputIds.length
              + "/"
              + attentionMask.length
              + "/"
              + tokenTypeIds.length);
    }
  }

  /** Packs encodings into buffers padded to the longest sequence in the batch. */
  public static PaddedTokenBatch of(Encoding[] encodings) {
    int batch = encodings.length;
    long[][] ids = new long[batch][];
    long[][] masks = new long[batch][];
    long[][] types = new long[batch][];
    for (int b = 0; b < batch; b++) {
      ids[b] = encodings[b].getIds();
      masks[b] = encodings[b].getAttentionMask();
      types[b] = encodings[b].getTypeIds();
    }
    return pack(ids, masks, types);
  }

  /**
   * The pure packing step, split out so it can be tested without a tokenizer.
   *
   * @param ids per-sequence token ids
   * @param masks per-sequence attention masks
   * @param types per-sequence token type ids; may be shorter than {@code ids}
   *     for models that do not use them
   */
  static PaddedTokenBatch pack(long[][] ids, long[][] masks, long[][] types) {
    int batch = ids.length;
    int maxLength = 0;
    for (long[] sequence : ids) {
      maxLength = Math.max(maxLength, sequence.length);
    }

    long[] inputIds = new long[batch * maxLength];
    long[] attentionMask = new long[batch * maxLength];
    long[] tokenTypeIds = new long[batch * maxLength];

    for (int b = 0; b < batch; b++) {
      long[] sequenceIds = ids[b];
      long[] sequenceMask = masks[b];
      long[] sequenceTypes = types[b];
      int length = sequenceIds.length;
      int base = b * maxLength;

      System.arraycopy(sequenceIds, 0, inputIds, base, length);
      System.arraycopy(sequenceMask, 0, attentionMask, base, Math.min(length, sequenceMask.length));
      if (sequenceTypes.length == length) {
        System.arraycopy(sequenceTypes, 0, tokenTypeIds, base, length);
      }
      // The remaining slots are already zero: right padding with the pad token
      // and an attention mask of 0.
    }
    return new PaddedTokenBatch(inputIds, attentionMask, tokenTypeIds, batch, maxLength);
  }
}
