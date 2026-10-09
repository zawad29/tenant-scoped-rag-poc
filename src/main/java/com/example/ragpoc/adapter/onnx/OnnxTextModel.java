package com.example.ragpoc.adapter.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a local ONNX transformer and runs batches through it.
 *
 * <p>Deliberately independent of Spring AI: {@code TransformersEmbeddingModel}
 * hard codes mean pooling, which is wrong for bge (see {@link PoolingMode}), and
 * it drags in {@code pytorch-engine} and {@code model-zoo}. Running the session
 * ourselves is a few dozen lines and gives exact control over pooling,
 * truncation, padding, normalisation and batching.
 *
 * <p>Both the tokenizer's Rust native library and the ONNX Runtime native
 * library ship inside their jars, and the model files are read from disk, so
 * this class performs no network access at runtime.
 *
 * <p>Not thread safe: {@link OrtSession} and the tokenizer are shared, so callers
 * must serialise access (see the adapter-level synchronization).
 */
public final class OnnxTextModel implements AutoCloseable {

  private static final String INPUT_IDS = "input_ids";
  private static final String ATTENTION_MASK = "attention_mask";
  private static final String TOKEN_TYPE_IDS = "token_type_ids";

  private final OrtEnvironment environment;
  private final OrtSession session;
  private final HuggingFaceTokenizer tokenizer;
  private final Set<String> sessionInputs;
  private final int maxSequenceLength;

  private OnnxTextModel(
      OrtEnvironment environment,
      OrtSession session,
      HuggingFaceTokenizer tokenizer,
      int maxSequenceLength) {
    this.environment = environment;
    this.session = session;
    this.tokenizer = tokenizer;
    this.sessionInputs = Set.copyOf(session.getInputNames());
    this.maxSequenceLength = maxSequenceLength;

    if (!sessionInputs.contains(INPUT_IDS)) {
      throw new IllegalStateException(
          "Model does not accept an '" + INPUT_IDS + "' input; found " + sessionInputs);
    }
  }

  /**
   * Loads a model and its tokenizer from local files.
   *
   * @param modelPath path to a {@code .onnx} file
   * @param tokenizerPath path to the matching {@code tokenizer.json}
   * @param maxSequenceLength sequences are truncated to this many tokens
   * @param intraOpThreads ONNX Runtime compute threads; kept low on purpose so
   *     concurrent requests do not oversubscribe the host
   * @throws IOException if a file is missing or the model cannot be loaded; the
   *     message names the file, because this only fails at deployment time and
   *     the operator needs to know which artefact is wrong
   */
  public static OnnxTextModel load(
      Path modelPath, Path tokenizerPath, int maxSequenceLength, int intraOpThreads)
      throws IOException {

    requireReadable(modelPath, "ONNX model");
    requireReadable(tokenizerPath, "tokenizer");

    HuggingFaceTokenizer tokenizer =
        HuggingFaceTokenizer.builder()
            .optTokenizerPath(tokenizerPath)
            .optTruncation(true)
            .optMaxLength(maxSequenceLength)
            // Padding is applied per batch instead, so short texts never pay for
            // 512 tokens of compute.
            .optPadding(false)
            .build();

    try {
      OrtEnvironment environment = OrtEnvironment.getEnvironment();
      OrtSession.SessionOptions options = new OrtSession.SessionOptions();
      options.setIntraOpNumThreads(Math.max(1, intraOpThreads));
      options.setInterOpNumThreads(1);
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

      OrtSession session = environment.createSession(modelPath.toString(), options);
      return new OnnxTextModel(environment, session, tokenizer, maxSequenceLength);
    } catch (OrtException e) {
      // Do not leak a native handle when construction fails.
      tokenizer.close();
      throw new IOException(
          "Could not load the ONNX model at "
              + modelPath.toAbsolutePath()
              + ". The file may be corrupt, truncated, or exported for an incompatible "
              + "ONNX Runtime version (this build uses "
              + OrtEnvironment.getEnvironment().getVersion()
              + "). Re-run scripts/fetch-models.sh to replace it.",
          e);
    }
  }

  private static void requireReadable(Path path, String what) throws IOException {
    if (!Files.isReadable(path)) {
      throw new IOException(
          what
              + " not found at "
              + path.toAbsolutePath()
              + ". Run scripts/fetch-models.sh to download the local ONNX models.");
    }
  }

  public int maxSequenceLength() {
    return maxSequenceLength;
  }

  /**
   * Unpadded token-level output for a batch of single sequences.
   *
   * <p>{@code hiddenStates} is indexed as {@code [batch][sequence][hidden]} and
   * is padded to the longest sequence in the batch, so {@code lengths} must be
   * used to exclude padding when pooling over tokens.
   */
  public record SequenceBatch(float[][][] hiddenStates, int[] lengths) {}

  /**
   * Runs single sequences (for sentence embeddings).
   *
   * @see SequenceBatch
   */
  public SequenceBatch forwardSequences(List<String> texts) throws OrtException {
    if (texts.isEmpty()) {
      return new SequenceBatch(new float[0][][], new int[0]);
    }
    Encoding[] encodings = tokenizer.batchEncode(texts);

    try (OrtSession.Result result = run(encodings)) {
      OnnxTensor output = firstTensor(result);
      long[] shape = output.getInfo().getShape();
      if (shape.length != 3) {
        throw new IllegalStateException(
            "Expected a [batch, sequence, hidden] output for embeddings but got shape "
                + java.util.Arrays.toString(shape));
      }
      int batch = (int) shape[0];
      int sequence = (int) shape[1];
      int hidden = (int) shape[2];

      FloatBuffer buffer = output.getFloatBuffer();
      float[][][] vectors = new float[batch][sequence][hidden];
      float[] flat = new float[sequence * hidden];
      for (int b = 0; b < batch; b++) {
        buffer.position(b * sequence * hidden);
        buffer.get(flat, 0, sequence * hidden);
        for (int t = 0; t < sequence; t++) {
          System.arraycopy(flat, t * hidden, vectors[b][t], 0, hidden);
        }
      }

      int[] lengths = new int[batch];
      for (int b = 0; b < batch; b++) {
        lengths[b] = encodings[b].getIds().length;
      }
      return new SequenceBatch(vectors, lengths);
    }
  }

  /**
   * Runs query/passage pairs (for cross-encoder reranking).
   *
   * <p>Packing the pair into one sequence with distinguishing token type ids is
   * what lets the model attend across the query and the passage.
   *
   * @return {@code [batch][labels]} raw logits
   */
  public float[][] forwardPairs(List<String[]> pairs) throws OrtException {
    if (pairs.isEmpty()) {
      return new float[0][];
    }
    Encoding[] encodings = new Encoding[pairs.size()];
    for (int i = 0; i < pairs.size(); i++) {
      String[] pair = pairs.get(i);
      encodings[i] = tokenizer.encode(pair[0], pair[1]);
    }

    try (OrtSession.Result result = run(encodings)) {
      OnnxTensor output = firstTensor(result);
      long[] shape = output.getInfo().getShape();
      if (shape.length > 2) {
        throw new IllegalStateException(
            "Expected a [batch, labels] output for a cross-encoder but got shape "
                + java.util.Arrays.toString(shape));
      }
      int batch = (int) shape[0];
      int labels = shape.length == 1 ? 1 : (int) shape[1];

      FloatBuffer buffer = output.getFloatBuffer();
      float[][] scores = new float[batch][labels];
      for (int b = 0; b < batch; b++) {
        buffer.position(b * labels);
        buffer.get(scores[b], 0, labels);
      }
      return scores;
    }
  }

  /** Runs only the inputs the model actually declares. */
  private OrtSession.Result run(Encoding[] encodings) throws OrtException {
    int batch = encodings.length;
    int maxLength = 0;
    for (Encoding encoding : encodings) {
      maxLength = Math.max(maxLength, encoding.getIds().length);
    }

    LongBuffer ids = LongBuffer.allocate(batch * maxLength);
    LongBuffer masks = LongBuffer.allocate(batch * maxLength);
    LongBuffer types = LongBuffer.allocate(batch * maxLength);

    for (Encoding encoding : encodings) {
      long[] encodingIds = encoding.getIds();
      long[] encodingMask = encoding.getAttentionMask();
      long[] encodingTypes = encoding.getTypeIds();
      int length = encodingIds.length;

      ids.put(encodingIds);
      masks.put(encodingMask);
      if (encodingTypes.length == length) {
        types.put(encodingTypes);
      } else {
        // Some models (e.g. XLM-R based) do not use token type ids.
        types.position(types.position() + length);
      }

      // Right-pad with the pad token id (0 for BERT-family vocabularies) and
      // exclude the padding from attention.
      for (int i = length; i < maxLength; i++) {
        ids.put(0L);
        masks.put(0L);
        types.put(0L);
      }
    }

    ids.rewind();
    masks.rewind();
    types.rewind();

    Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
    List<OnnxTensor> created = new ArrayList<>(3);
    long[] shape = {batch, maxLength};
    try {
      created.add(OnnxTensor.createTensor(environment, ids, shape));
      inputs.put(INPUT_IDS, created.get(created.size() - 1));

      if (sessionInputs.contains(ATTENTION_MASK)) {
        created.add(OnnxTensor.createTensor(environment, masks, shape));
        inputs.put(ATTENTION_MASK, created.get(created.size() - 1));
      }
      if (sessionInputs.contains(TOKEN_TYPE_IDS)) {
        created.add(OnnxTensor.createTensor(environment, types, shape));
        inputs.put(TOKEN_TYPE_IDS, created.get(created.size() - 1));
      }

      // ONNX Runtime copies input data during run(), so the input tensors can
      // be released as soon as run() returns. The result owns separate native
      // memory and is closed by the caller.
      OrtSession.Result result = session.run(inputs);
      releaseQuietly(created);
      return result;
    } catch (OrtException | RuntimeException e) {
      releaseQuietly(created);
      throw e;
    }
  }

  private static void releaseQuietly(List<OnnxTensor> tensors) {
    for (OnnxTensor tensor : tensors) {
      try {
        tensor.close();
      } catch (RuntimeException ignored) {
        // Releasing a failed batch must not mask the original failure.
      }
    }
  }

  private static OnnxTensor firstTensor(OrtSession.Result result) throws OrtException {
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

  @Override
  public void close() {
    try {
      session.close();
    } catch (OrtException e) {
      // Closing failures are not actionable.
    }
    tokenizer.close();
  }
}
