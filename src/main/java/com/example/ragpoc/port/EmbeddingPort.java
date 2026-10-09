package com.example.ragpoc.port;

import java.util.List;

/**
 * Turns text into embedding vectors.
 *
 * <p>Implementations are responsible for applying the pooling strategy their
 * model requires and for recording the model identity, because vectors from
 * different models are never comparable and must never share an index.
 */
public interface EmbeddingPort {

  /** Stable identifier of the model, stored alongside every vector. */
  String modelId();

  /** Vector length. Must match the vector column's declared dimension. */
  int dimension();

  float[] embed(String text);

  /**
   * Embeds a batch of texts.
   *
   * @return one vector per input, in input order
   */
  List<float[]> embedAll(List<String> texts);
}
