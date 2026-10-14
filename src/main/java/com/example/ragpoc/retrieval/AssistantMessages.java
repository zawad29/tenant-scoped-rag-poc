package com.example.ragpoc.retrieval;

/**
 * Fixed wording the assistant uses.
 *
 * <p>The refusal is a constant rather than a model output on purpose. When the
 * relevance gate decides there is nothing to ground an answer in, no model call
 * happens at all, so the message cannot be argued with and cannot drift.
 */
public final class AssistantMessages {

  private AssistantMessages() {}

  public static final String REFUSAL =
      "I couldn't find this in your organisation's documents, so I can't answer it. "
          + "Try rephrasing, or ask about a topic covered by your uploaded documents.";

  public static final String REJECTED_INPUT =
      "I can't process that request. Please ask a question about your organisation's documents.";

  public static final String GENERIC_ERROR =
      "Something went wrong while answering that question. Please try again.";

  /**
   * No chat provider is configured (no starter active, no reachable model). The
   * key is named because that is the usual cause.
   */
  public static final String CHAT_UNAVAILABLE =
      "Chat is not configured, so this question was not sent to a model. "
          + "Set DEEPSEEK_API_KEY and restart the application.";
}
