package com.example.ragpoc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ragpoc.chat.ChatEvent;
import com.example.ragpoc.chat.ChatTimings;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.cid.entity.Cid;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.retrieval.AssistantMessages;
import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.retrieval.model.RetrievalTrace;
import com.example.ragpoc.retrieval.model.RetrievedChunk;
import com.example.ragpoc.retrieval.service.RetrievalService;
import com.example.ragpoc.user.entity.Role;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/**
 * The orchestration, with retrieval and the model both mocked.
 *
 * <p>What matters here is the order and shape of the events, and that a refusal
 * never reaches the model: those are the two things the controller and the
 * JavaScript are written against.
 */
@SuppressWarnings("unchecked")
class ChatServiceTest {

  private final UUID cidId = UUID.randomUUID();
  private final CidContext cid = CidContext.of(cidId, UUID.randomUUID(), Role.CID_ADMIN);

  private RetrievalService retrieval;
  private ObjectProvider<RetrievalService> retrievalProvider;
  private ChatClient chatClient;
  private ChatClient.ChatClientRequestSpec request;
  private ChatClient.StreamResponseSpec stream;
  private ChatClient.CallResponseSpec call;
  private ChatService chat;

  @BeforeEach
  void setUp() {
    retrieval = mock(RetrievalService.class);
    chatClient = mock(ChatClient.class);
    request = mock(ChatClient.ChatClientRequestSpec.class);
    stream = mock(ChatClient.StreamResponseSpec.class);
    call = mock(ChatClient.CallResponseSpec.class);

    when(chatClient.prompt()).thenReturn(request);
    when(request.messages(any(Message[].class))).thenReturn(request);
    when(request.stream()).thenReturn(stream);
    when(request.call()).thenReturn(call);

    retrievalProvider = mock(ObjectProvider.class);
    when(retrievalProvider.getObject()).thenReturn(retrieval);

    ObjectProvider<ChatClient> clientProvider = mock(ObjectProvider.class);
    when(clientProvider.getIfAvailable()).thenReturn(chatClient);

    chat = new ChatService(retrievalProvider, clientProvider, cidService());
  }

  @Test
  @DisplayName("stream emits retrieval, then the deltas, then the generation timings")
  void streamEmitsDeltasThenTimings() {
    RetrievalOutcome outcome = answerable("How much leave?");
    when(retrieval.retrieve(cid, "How much leave?")).thenReturn(outcome);
    when(stream.content()).thenReturn(Flux.just("Eighteen ", "days. [S1]"));

    List<ChatEvent> events = chat.stream(cid, "How much leave?").collectList().block();

    assertThat(events).hasSize(4);
    assertThat(events.get(0)).isInstanceOf(ChatEvent.Retrieved.class);
    assertThat(events.get(1)).isEqualTo(new ChatEvent.Delta("Eighteen "));
    assertThat(events.get(2)).isEqualTo(new ChatEvent.Delta("days. [S1]"));

    ChatEvent.Completed completed = (ChatEvent.Completed) events.get(3);
    assertThat(completed.outcome()).isSameAs(outcome);
    assertThat(completed.timings().refused()).isFalse();
    assertThat(completed.timings().totalMillis()).isGreaterThanOrEqualTo(0);
    assertThat(completed.timings().generationMillis()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("a refusal streams fixed wording and never calls the model")
  void refusalDoesNotCallTheModel() {
    RetrievalOutcome outcome = refused("Anything about zebras?");
    when(retrieval.retrieve(any(), any())).thenReturn(outcome);

    List<ChatEvent> events = chat.stream(cid, "Anything about zebras?").collectList().block();

    assertThat(events).hasSize(3);
    assertThat(events.get(0)).isInstanceOf(ChatEvent.Retrieved.class);
    assertThat(events.get(1)).isEqualTo(new ChatEvent.Delta(AssistantMessages.REFUSAL));
    assertThat(((ChatEvent.Completed) events.get(2)).timings().refused()).isTrue();
    verifyNoInteractions(chatClient);
  }

  @Test
  @DisplayName("a retrieval failure is an event, not an exception")
  void retrievalFailureBecomesAnEvent() {
    when(retrieval.retrieve(any(), any()))
        .thenThrow(new IllegalStateException("Tokenizer not found"));

    List<ChatEvent> events = chat.stream(cid, "q").collectList().block();

    assertThat(events).hasSize(1);
    assertThat(((ChatEvent.Failed) events.get(0)).message()).contains("fetch-models.sh");
    verifyNoInteractions(chatClient);
  }

  @Test
  @DisplayName("an absent chat client is reported after retrieval, not instead of it")
  void missingChatClientIsReported() {
    when(retrieval.retrieve(any(), any())).thenReturn(answerable("q"));
    ObjectProvider<ChatClient> absent = mock(ObjectProvider.class);
    when(absent.getIfAvailable()).thenReturn(null);
    ChatService service = new ChatService(retrievalProvider, absent, cidService());

    List<ChatEvent> events = service.stream(cid, "q").collectList().block();

    assertThat(events).hasSize(2);
    assertThat(events.get(0)).isInstanceOf(ChatEvent.Retrieved.class);
    assertThat(events.get(1)).isEqualTo(new ChatEvent.Failed(AssistantMessages.CHAT_UNAVAILABLE));
  }

  @Test
  @DisplayName("ask returns the whole answer with its timings")
  void askReturnsTheCompleteAnswer() {
    when(retrieval.retrieve(any(), any())).thenReturn(answerable("q"));
    when(call.content()).thenReturn("Eighteen days. [S1]");

    ChatService.ChatReply reply = chat.ask(cid, "q");

    assertThat(reply.answer()).isEqualTo("Eighteen days. [S1]");
    assertThat(reply.outcome().canAnswer()).isTrue();
    assertThat(reply.timings().refused()).isFalse();
    assertThat(reply.timings().generationMillis()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("ask refuses with fixed wording and never calls the model")
  void askRefusesWithoutCallingTheModel() {
    when(retrieval.retrieve(any(), any())).thenReturn(refused("q"));

    ChatService.ChatReply reply = chat.ask(cid, "q");

    assertThat(reply.answer()).isEqualTo(AssistantMessages.REFUSAL);
    assertThat(reply.timings().refused()).isTrue();
    verifyNoInteractions(chatClient);
  }

  @Test
  @DisplayName("provider failures become wording the user can act on")
  void providerFailuresAreExplained() {
    assertThat(ChatService.friendlyMessage(new RuntimeException("401 Unauthorized")))
        .contains("DEEPSEEK_API_KEY");
    assertThat(ChatService.friendlyMessage(new RuntimeException("402 Insufficient Balance")))
        .contains("credit");
    assertThat(ChatService.friendlyMessage(new RuntimeException("429 Too Many Requests")))
        .contains("rate limiting");
    assertThat(ChatService.friendlyMessage(new RuntimeException("boom")))
        .isEqualTo(AssistantMessages.GENERIC_ERROR);
  }

  // --- fixtures -------------------------------------------------------------

  private CidService cidService() {
    CidService cids = mock(CidService.class);
    when(cids.require(cidId)).thenReturn(Cid.create("Canary A", null, null));
    return cids;
  }

  private static RetrievalOutcome answerable(String question) {
    RetrievedChunk chunk =
        new RetrievedChunk(
            "S1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "LEAVE",
            "Employee Handbook",
            "Employees accrue eighteen days of annual leave.",
            0.910,
            5.420,
            0.995);
    RetrievalTrace trace =
        new RetrievalTrace(
            question,
            "answer",
            "ok",
            1,
            1,
            1,
            5.420,
            0.35,
            0.15,
            "all-MiniLM-L6-v2",
            "ms-marco-MiniLM-L-6-v2",
            1,
            2,
            3,
            4,
            List.of(),
            List.of());
    return RetrievalOutcome.answerable(List.of(chunk), trace);
  }

  private static RetrievalOutcome refused(String question) {
    RetrievalTrace trace =
        new RetrievalTrace(
            question,
            "refused",
            "no candidates retrieved",
            0,
            0,
            0,
            Double.NEGATIVE_INFINITY,
            0.35,
            0.15,
            "all-MiniLM-L6-v2",
            "ms-marco-MiniLM-L-6-v2",
            1,
            2,
            0,
            0,
            List.of(),
            List.of());
    return RetrievalOutcome.refused(trace, AssistantMessages.REFUSAL);
  }
}
