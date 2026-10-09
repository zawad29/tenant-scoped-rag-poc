package com.example.ragpoc.chat;

/**
 * Fixed wording the assistant uses.
 *
 * <p>The refusal is a constant rather than a model output on purpose. When the
 * relevance gate decides there is nothing to ground an answer in, no model call
 * happens at all, so the message cannot be talked out of refusing and cannot
 * drift between runs.
 */
public final class AssistantMessages {

  private AssistantMessages() {}

  /** Specification O6. */
  public static final String REFUSAL =
      "I couldn't find this in your organisation's documents, so I can't answer it. "
          + "Try rephrasing, or ask about a topic covered by your uploaded documents.";

  /** Used when a question is rejected before any retrieval happens. */
  public static final String REJECTED_INPUT =
      "I can't process that request. Please ask a question about your organisation's documents.";

  /** Used for greetings and meta questions, which need no retrieval. */
  public static final String SMALL_TALK =
      "I answer questions using the documents your organisation has uploaded. "
          + "Ask me about their contents and I'll cite the pages I used.";

  /**
   * Used when a tenant check fails after retrieval.
   *
   * <p>Deliberately generic. A specific message would confirm that something
   * unusual happened, and this path should be unreachable anyway: it exists to
   * turn a filter bug into a failed request instead of a wrong answer.
   */
  public static final String GENERIC_ERROR =
      "Something went wrong while answering that question. Please try again.";
}
