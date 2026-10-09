package com.example.ragpoc.adapter.onnx;

/**
 * How token-level model output is collapsed into a single sentence vector.
 *
 * <p>This is not a free choice: each model family was trained with one specific
 * strategy, and using the wrong one produces vectors that still look plausible
 * (right dimension, roughly unit length) while quietly wrecking retrieval.
 *
 * <ul>
 *   <li>{@link #CLS} — BERT-style models, including all bge models. Confirmed by
 *       {@code 1_Pooling/config.json} in BAAI/bge-base-en-v1.5:
 *       {@code pooling_mode_cls_token=true}.
 *   <li>{@link #MEAN} — sentence-transformers models such as all-MiniLM-L6-v2
 *       and the e5 family.
 * </ul>
 */
public enum PoolingMode {
  /** Use the first token's hidden state. */
  CLS,

  /** Average token hidden states, weighting by the attention mask. */
  MEAN
}
