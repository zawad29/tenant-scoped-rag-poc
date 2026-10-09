package com.example.ragpoc.retrieval;

import java.util.List;

/**
 * What retrieval produced for one question.
 *
 * @param refusalMessage null when the evidence was good enough to answer from;
 *     otherwise the fixed message to return without calling the model
 */
public record RetrievalOutcome(
    List<RetrievedChunk> chunks, RetrievalTrace trace, String refusalMessage) {

  public boolean canAnswer() {
    return refusalMessage == null;
  }

  static RetrievalOutcome refused(RetrievalTrace trace, String message) {
    return new RetrievalOutcome(List.of(), trace, message);
  }

  static RetrievalOutcome answerable(List<RetrievedChunk> chunks, RetrievalTrace trace) {
    return new RetrievalOutcome(chunks, trace, null);
  }
}
