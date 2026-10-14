package com.example.ragpoc.chat;

/**
 * Raised when no {@code ChatClient} is configured, which is the state of a
 * deployment with no provider starter active or no reachable model.
 *
 * <p>It exists as its own type so the failure can be told apart from a provider
 * error: the message is fixed wording, not something parsed from a response.
 */
public class ChatUnavailableException extends RuntimeException {

  public ChatUnavailableException(String message) {
    super(message);
  }
}
