package com.example.ragpoc.adapter.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.example.ragpoc.ingest.TokenCounter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Token counting with the same tokenizer the embedding model uses.
 *
 * <p>Truncation and padding are off: a count must be the true length of the
 * text, otherwise an over-long chunk would be reported as exactly 512 tokens
 * and the truncation would go unnoticed.
 */
public final class HfTokenCounter implements TokenCounter, AutoCloseable {

  private final HuggingFaceTokenizer tokenizer;

  public HfTokenCounter(Path tokenizerPath) throws IOException {
    if (!Files.isReadable(tokenizerPath)) {
      throw new IOException(
          "Tokenizer not found at "
              + tokenizerPath.toAbsolutePath()
              + ". Run scripts/fetch-models.sh to download the local ONNX models.");
    }
    this.tokenizer =
        HuggingFaceTokenizer.builder()
            .optTokenizerPath(tokenizerPath)
            .optTruncation(false)
            .optPadding(false)
            .build();
  }

  @Override
  public int count(String text) {
    if (text == null || text.isBlank()) {
      return 0;
    }
    // The HuggingFace tokenizer natively iterates at high speed; synchronized
    // because the underlying native handle is not thread safe.
    synchronized (tokenizer) {
      return tokenizer.encode(text).getIds().length;
    }
  }

  @Override
  public void close() {
    tokenizer.close();
  }
}
