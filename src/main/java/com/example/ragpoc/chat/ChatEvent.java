package com.example.ragpoc.chat;

import com.example.ragpoc.retrieval.model.RetrievalOutcome;

/**
 * One step of an answer, as produced by {@link com.example.ragpoc.chat.service.ChatService}
 * and turned into a server-sent event by the controller.
 *
 * <p>The order is: {@link Retrieved}, zero or more {@link Delta}, then either
 * {@link Completed} or {@link Failed}. Retrieval comes first and on its own so
 * the panel can render while the model is still generating.
 *
 * <p>These are transport-neutral: nothing here knows about HTTP, and in
 * particular nothing here is HTML. The controller decides how each event is
 * rendered for the browser.
 */
public sealed interface ChatEvent {

  /** Retrieval finished; the panel can be drawn. */
  record Retrieved(RetrievalOutcome outcome, long retrievalMillis) implements ChatEvent {}

  /** The next slice of the answer text, appended in order. */
  record Delta(String text) implements ChatEvent {}

  /**
   * The answer is complete. It carries only the timings: the panel already sent
   * the retrieval result and is not rebuilt, so the collapsible sections the user
   * opened stay open.
   */
  record Completed(ChatTimings timings) implements ChatEvent {}

  /** No answer will follow; the message is safe to show to the user. */
  record Failed(String message) implements ChatEvent {}
}
