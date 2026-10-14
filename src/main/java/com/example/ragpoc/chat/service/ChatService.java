package com.example.ragpoc.chat.service;

import com.example.ragpoc.chat.ChatEvent;
import com.example.ragpoc.chat.ChatPrompts;
import com.example.ragpoc.chat.ChatTimings;
import com.example.ragpoc.chat.ChatUnavailableException;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.retrieval.AssistantMessages;
import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.retrieval.service.RetrievalService;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * One grounded answer: retrieve, decide, generate.
 *
 * <p>Retrieval is delegated to {@link RetrievalService}, so this class adds no
 * knowledge access of its own. When the relevance gate refuses, no model call
 * happens here either: the refusal is streamed as fixed wording, which is why
 * the timings can mark a request as refused.
 *
 * <p>The prompt is built from the assembled chunks only. Nothing else about the
 * cid reaches the model, and history is deliberately absent: there is no memory
 * yet, so each question is answered from a fresh retrieval.
 *
 * <p>Both entry points share the same prompt and the same refusal handling.
 * {@link #stream} is the interactive path and reports events as they happen;
 * {@link #ask} is the blocking path behind the no-JavaScript form fallback.
 */
@Slf4j
public class ChatService {

  private final ObjectProvider<RetrievalService> retrieval;
  private final ObjectProvider<ChatClient> chatClient;
  private final CidService cids;

  public ChatService(
      ObjectProvider<RetrievalService> retrieval,
      ObjectProvider<ChatClient> chatClient,
      CidService cids) {
    this.retrieval = retrieval;
    this.chatClient = chatClient;
    this.cids = cids;
  }

  /** A complete answer, produced without streaming. */
  public record ChatReply(RetrievalOutcome outcome, String answer, ChatTimings timings) {}

  /** Retrieval plus a blocking model call, for the plain-form fallback. */
  public ChatReply ask(CidContext cid, String question) {
    long start = System.nanoTime();
    RetrievalOutcome outcome = retrieval.getObject().retrieve(cid, question);
    long retrievalMillis = elapsed(start);

    if (!outcome.canAnswer()) {
      log.info("chat refuse cid={} reason={}", cid.cidId(), outcome.trace().reason());
      return new ChatReply(
          outcome,
          outcome.refusalMessage(),
          new ChatTimings(retrievalMillis, 0, 0, elapsed(start), true));
    }

    ChatClient client = requireChatClient();
    long generationStart = System.nanoTime();
    String answer =
        client
            .prompt()
            .messages(
                new SystemMessage(ChatPrompts.system(cidName(cid))),
                new UserMessage(ChatPrompts.user(outcome.trace().question(), outcome.chunks())))
            .call()
            .content();
    long generationMillis = elapsed(generationStart);

    log.info(
        "chat done cid={} retrievalMillis={} generationMillis={} answerChars={}",
        cid.cidId(), retrievalMillis, generationMillis, answer == null ? 0 : answer.length());
    return new ChatReply(
        outcome,
        answer == null ? AssistantMessages.GENERIC_ERROR : answer,
        new ChatTimings(retrievalMillis, 0, generationMillis, elapsed(start), false));
  }

  /**
   * Retrieval plus a streamed model call.
   *
   * <p>Retrieval runs before the first event is returned: it has to, because the
   * answer depends on its outcome, and the browser shows its own pending state
   * until the panel arrives. A retrieval failure therefore becomes a single
   * {@link ChatEvent.Failed} rather than an HTTP error, so the page stays usable.
   */
  public Flux<ChatEvent> stream(CidContext cid, String question) {
    long start = System.nanoTime();

    RetrievalOutcome outcome;
    try {
      outcome = retrieval.getObject().retrieve(cid, question);
    } catch (RuntimeException e) {
      log.warn("Chat retrieval failed for cid {}: {}", cid.cidId(), e.toString());
      return Flux.just(new ChatEvent.Failed(friendlyMessage(e)));
    }
    long retrievalMillis = elapsed(start);

    if (!outcome.canAnswer()) {
      log.info("chat refuse cid={} reason={}", cid.cidId(), outcome.trace().reason());
      return Flux.just(
          new ChatEvent.Retrieved(outcome, retrievalMillis),
          new ChatEvent.Delta(outcome.refusalMessage()),
          new ChatEvent.Completed(
              outcome, new ChatTimings(retrievalMillis, 0, 0, elapsed(start), true)));
    }

    ChatClient client = chatClientOrNull();
    if (client == null) {
      log.warn("Chat request for cid {} has no configured ChatClient", cid.cidId());
      return Flux.just(
          new ChatEvent.Retrieved(outcome, retrievalMillis),
          new ChatEvent.Failed(AssistantMessages.CHAT_UNAVAILABLE));
    }

    SystemMessage system = new SystemMessage(ChatPrompts.system(cidName(cid)));
    UserMessage user = new UserMessage(ChatPrompts.user(outcome.trace().question(), outcome.chunks()));

    long generationStart = System.nanoTime();
    AtomicLong firstTokenMillis = new AtomicLong(0);

    Flux<ChatEvent> deltas =
        client
            .prompt()
            .messages(system, user)
            .stream()
            .content()
            .doOnNext(token -> firstTokenMillis.compareAndSet(0, elapsed(generationStart)))
            .map(ChatEvent.Delta::new);

    // Deferred so it reads the timings only after the last delta has arrived.
    Flux<ChatEvent> completed =
        Flux.defer(
            () ->
                Flux.just(
                    new ChatEvent.Completed(
                        outcome,
                        new ChatTimings(
                            retrievalMillis,
                            firstTokenMillis.get(),
                            elapsed(generationStart),
                            elapsed(start),
                            false))));

    return Flux.concat(
            Flux.just(new ChatEvent.Retrieved(outcome, retrievalMillis)), deltas, completed)
        .onErrorResume(
            error -> {
              log.error("Chat generation failed for cid {}", cid.cidId(), error);
              return Flux.just(new ChatEvent.Failed(friendlyMessage(error)));
            });
  }

  private String cidName(CidContext cid) {
    return cids.require(cid.cidId()).getName();
  }

  private ChatClient requireChatClient() {
    ChatClient client = chatClientOrNull();
    if (client == null) {
      throw new ChatUnavailableException(AssistantMessages.CHAT_UNAVAILABLE);
    }
    return client;
  }

  /**
   * A missing provider is not an error here: the autoconfiguration is absent
   * rather than broken, and the caller decides how to report it.
   */
  private ChatClient chatClientOrNull() {
    try {
      return chatClient.getIfAvailable();
    } catch (RuntimeException e) {
      log.warn("No usable ChatClient: {}", e.toString());
      return null;
    }
  }

  /**
   * Maps a provider failure to wording the user can act on, without echoing a
   * response body that may contain account details.
   */
  public static String friendlyMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    if (root instanceof ChatUnavailableException) {
      return AssistantMessages.CHAT_UNAVAILABLE;
    }
    String message = root.getMessage() == null ? "" : root.getMessage();
    if (message.contains("401")
        || message.contains("Unauthorized")
        || message.contains("Authentication Fails")) {
      return "The language model rejected the API key. Check DEEPSEEK_API_KEY.";
    }
    if (message.contains("402") || message.contains("Insufficient Balance")) {
      return "The language model account has no remaining credit.";
    }
    if (message.contains("429") || message.contains("rate limit") || message.contains("Too Many")) {
      return "The language model is rate limiting requests right now. Please try again shortly.";
    }
    if (message.contains("Model Not Exist") || message.contains("model not found")) {
      return "The configured model is not available from the provider. Check DEEPSEEK_MODEL.";
    }
    if (message.contains("Tokenizer not found")
        || message.contains("ONNX")
        || message.contains("onnxruntime")) {
      return "Retrieval models are not available. Run scripts/fetch-models.sh, then restart the application.";
    }
    return AssistantMessages.GENERIC_ERROR;
  }

  private static long elapsed(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
