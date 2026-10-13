package com.example.ragpoc.retrieval.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts between {@link PaddedTokenBatch}es and ONNX Runtime tensors.
 *
 * <p>All the buffer arithmetic lives here: building the input tensors, reading
 * the first output tensor back into Java arrays, and releasing native handles.
 * It owns no session, so it is a set of static operations rather than a bean.
 */
public final class OnnxTensors {

  public static final String INPUT_IDS = "input_ids";
  public static final String ATTENTION_MASK = "attention_mask";
  public static final String TOKEN_TYPE_IDS = "token_type_ids";

  private OnnxTensors() {}

  /**
   * Builds the input tensors the model actually declares.
   *
   * <p>Some models (XLM-R, for example) take no token type ids; feeding one
   * anyway is an error, so only declared inputs are created.
   */
  public static Map<String, OnnxTensor> toInputs(
      OrtEnvironment environment, Set<String> modelInputs, PaddedTokenBatch batch)
      throws OrtException {

    Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
    List<OnnxTensor> created = new ArrayList<>(3);
    long[] shape = {batch.batch(), batch.maxLength()};
    try {
      OnnxTensor ids =
          OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.inputIds()), shape);
      created.add(ids);
      inputs.put(INPUT_IDS, ids);

      if (modelInputs.contains(ATTENTION_MASK)) {
        OnnxTensor mask =
            OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.attentionMask()), shape);
        created.add(mask);
        inputs.put(ATTENTION_MASK, mask);
      }
      if (modelInputs.contains(TOKEN_TYPE_IDS)) {
        OnnxTensor types =
            OnnxTensor.createTensor(environment, LongBuffer.wrap(batch.tokenTypeIds()), shape);
        created.add(types);
        inputs.put(TOKEN_TYPE_IDS, types);
      }
      return inputs;
    } catch (OrtException | RuntimeException e) {
      // Do not leak a native handle when a later tensor fails to build.
      closeQuietly(created);
      throw e;
    }
  }

  /** The first tensor of a result, which is the primary output for both models. */
  public static OnnxTensor firstTensor(OrtSession.Result result) throws OrtException {
    OnnxValue value = result.get(0);
    if (value == null) {
      throw new IllegalStateException("Model produced no output");
    }
    if (!(value instanceof OnnxTensor tensor)) {
      throw new IllegalStateException(
          "Expected a tensor output but got " + value.getClass().getSimpleName());
    }
    return tensor;
  }

  /**
   * Reads a {@code [batch]} or {@code [batch, labels]} output into a matrix.
   *
   * <p>Cross-encoders emit a single relevance label per pair, so the common
   * shape is {@code [batch, 1]}; a bare {@code [batch]} is treated as one label
   * too.
   */
  public static float[][] scoreMatrix(OnnxTensor tensor) {
    long[] shape = tensor.getInfo().getShape();
    if (shape.length > 2) {
      throw new IllegalStateException(
          "Expected a [batch, labels] output but got shape " + java.util.Arrays.toString(shape));
    }
    int batch = (int) shape[0];
    int labels = shape.length == 1 ? 1 : (int) shape[1];

    FloatBuffer buffer = tensor.getFloatBuffer();
    float[][] scores = new float[batch][labels];
    for (int b = 0; b < batch; b++) {
      buffer.position(b * labels);
      buffer.get(scores[b], 0, labels);
    }
    return scores;
  }

  /** Releases input tensors once the runtime has copied what it needs. */
  public static void closeQuietly(Collection<OnnxTensor> tensors) {
    for (OnnxTensor tensor : tensors) {
      try {
        tensor.close();
      } catch (RuntimeException ignored) {
        // Releasing a failed batch must not mask the original failure.
      }
    }
  }
}
