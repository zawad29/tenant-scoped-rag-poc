package com.example.ragpoc.support;

import com.example.ragpoc.ingest.TokenCounter;

/** Token counters for tests that do not need the real tokenizer. */
public final class TestTokenCounters {

  private TestTokenCounters() {}

  /**
   * Counts whitespace-separated words.
   *
   * <p>Lets chunking behaviour be tested deterministically and quickly. The real
   * tokenizer is exercised separately, in the tests that assert the actual
   * context-window contract.
   */
  public static TokenCounter words() {
    return text -> {
      if (text == null || text.isBlank()) {
        return 0;
      }
      return text.trim().split("\\s+").length;
    };
  }

  /** Counts characters, for cases where a specific size is convenient. */
  public static TokenCounter characters() {
    return text -> text == null ? 0 : text.length();
  }
}
