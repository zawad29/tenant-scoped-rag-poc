package com.example.ragpoc.retrieval.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A local ONNX cross-encoder: scores a query and a passage jointly.
 *
 * <p>Lifecycle and tokenisation only. The buffer packing lives in
 * {@link PaddedTokenBatch} / {@link OnnxTensors} and the label handling in
 * {@link RerankScores}, so this class reads as "encode the pairs, run, read the
 * scores".
 *
 * <p>Raw logits are returned unchanged. Nothing here normalises them; the
 * relevance gate compares these exact numbers.
 *
 * <p>Not thread safe: {@link OrtSession} and the tokenizer are shared, so
 * callers must serialise access (see {@code OnnxReranker}).
 */
public final class OnnxCrossEncoder implements AutoCloseable {

  private final OrtEnvironment environment;
  private final OrtSession session;
  private final HuggingFaceTokenizer tokenizer;
  private final Set<String> sessionInputs;
  private final int maxSequenceLength;

  private OnnxCrossEncoder(
      OrtEnvironment environment,
      OrtSession session,
      HuggingFaceTokenizer tokenizer,
      int maxSequenceLength) {
    this.environment = environment;
    this.session = session;
    this.tokenizer = tokenizer;
    this.sessionInputs = Set.copyOf(session.getInputNames());
    this.maxSequenceLength = maxSequenceLength;

    if (!sessionInputs.contains(OnnxTensors.INPUT_IDS)) {
      throw new IllegalStateException(
          "Model does not accept an '"
              + OnnxTensors.INPUT_IDS
              + "' input; found "
              + sessionInputs);
    }
  }

  /**
   * Loads a cross-encoder and its tokenizer from local files.
   *
   * @param maxSequenceLength sequences are truncated to this many tokens
   * @param intraOpThreads ONNX Runtime compute threads; kept low so concurrent
   *     requests do not oversubscribe the host
   * @throws IOException if a file is missing or the model cannot be loaded; the
   *     message names the artefact, because this only fails at deployment time
   */
  public static OnnxCrossEncoder load(
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
            // the full sequence length.
            .optPadding(false)
            .build();

    try {
      OrtEnvironment environment = OrtEnvironment.getEnvironment();
      OrtSession.SessionOptions options = new OrtSession.SessionOptions();
      options.setIntraOpNumThreads(Math.max(1, intraOpThreads));
      options.setInterOpNumThreads(1);
      options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

      OrtSession session = environment.createSession(modelPath.toString(), options);
      return new OnnxCrossEncoder(environment, session, tokenizer, maxSequenceLength);
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
   * Scores each query/passage pair, in input order.
   *
   * <p>Packing the pair into one sequence with distinguishing token type ids is
   * what lets the model attend across the query and the passage.
   */
  public double[] score(String query, List<String> passages) throws OrtException {
    if (passages.isEmpty()) {
      return new double[0];
    }
    Encoding[] encodings = new Encoding[passages.size()];
    for (int i = 0; i < passages.size(); i++) {
      encodings[i] = tokenizer.encode(query, passages.get(i));
    }

    PaddedTokenBatch batch = PaddedTokenBatch.of(encodings);
    Map<String, OnnxTensor> inputs = OnnxTensors.toInputs(environment, sessionInputs, batch);
    try (OrtSession.Result result = session.run(inputs)) {
      return RerankScores.singleLogits(OnnxTensors.scoreMatrix(OnnxTensors.firstTensor(result)));
    } finally {
      // ONNX Runtime copies the input data during run(), so the input tensors can
      // be released as soon as run() returns.
      OnnxTensors.closeQuietly(inputs.values());
    }
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
