package com.example.ragpoc.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragpoc.chat.ChatEvent;
import com.example.ragpoc.chat.ChatTimings;
import com.example.ragpoc.chat.service.ChatService;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.retrieval.model.RetrievalTrace;
import com.example.ragpoc.retrieval.model.RetrievedChunk;
import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.support.IntegrationTestSupport;
import com.example.ragpoc.user.entity.Role;
import com.example.ragpoc.user.entity.User;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * The chat screen end to end, with {@link ChatService} mocked so the test needs
 * no models and no network and can assert exactly what each path renders.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerIT extends IntegrationTestSupport {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ChatController controller;
  @Autowired private DataSource dataSource;
  @Autowired private PasswordEncoder passwordEncoder;

  @MockitoBean private ChatService chat;

  private static final String EMAIL = "admin@canary.test";

  @BeforeEach
  void seed() {
    resetDatabase(dataSource);
    seedCidWithUser(dataSource, passwordEncoder, "Canary A", EMAIL, "pw", "CID_ADMIN");
  }

  @Test
  @DisplayName("an anonymous request is redirected to the login page")
  void anonymousIsRedirected() throws Exception {
    mockMvc
        .perform(get("/chat"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  @Test
  @DisplayName("the shell renders before a question is asked")
  void shellRenders() throws Exception {
    mockMvc
        .perform(get("/chat").with(user(principal())))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Chat")))
        .andExpect(content().string(containsString("Ask a question to see the retrieved candidates")));
  }

  @Test
  @DisplayName("the no-JavaScript fallback renders the answer beside the retrieval panel")
  void fallbackRendersAnswerAndPanel() throws Exception {
    RetrievalOutcome outcome = answerableOutcome("How much leave?");
    when(chat.ask(any(), any()))
        .thenReturn(
            new ChatService.ChatReply(
                outcome,
                "Employees accrue eighteen days of annual leave. [S1]",
                new ChatTimings(30, 0, 900, 960, false)));

    mockMvc
        .perform(
            post("/chat").with(user(principal())).with(csrf()).param("question", "How much leave?"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("How much leave?")))
        .andExpect(content().string(containsString("eighteen days of annual leave")))
        .andExpect(content().string(containsString("Answerable")))
        .andExpect(content().string(containsString("Employee Handbook")))
        // Selected source with its scores and the rerank timing.
        .andExpect(content().string(containsString("S1")))
        .andExpect(content().string(containsString("5.420")))
        .andExpect(content().string(containsString("Rerank")))
        // Generation timing is only known on the blocking path.
        .andExpect(content().string(containsString("Generation")));
  }

  @Test
  @DisplayName("a refusal renders the fixed message and no model call")
  void fallbackRendersRefusal() throws Exception {
    RetrievalOutcome outcome = refusedOutcome("Anything about zebras?");
    when(chat.ask(any(), any()))
        .thenReturn(
            new ChatService.ChatReply(
                outcome,
                outcome.refusalMessage(),
                new ChatTimings(12, 0, 0, 12, true)));

    mockMvc
        .perform(
            post("/chat")
                .with(user(principal()))
                .with(csrf())
                .param("question", "Anything about zebras?"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Refused")))
        .andExpect(content().string(containsString("couldn't find this")))
        .andExpect(content().string(containsString("No model call")));
  }

  @Test
  @DisplayName("the streaming endpoint sends panel, delta and completed frames")
  void streamingSendsEvents() throws Exception {
    RetrievalOutcome outcome = answerableOutcome("How much leave?");
    when(chat.stream(any(), any()))
        .thenReturn(
            Flux.just(
                new ChatEvent.Retrieved(outcome, 30),
                new ChatEvent.Delta("Eighteen "),
                new ChatEvent.Delta("days. [S1]"),
                new ChatEvent.Completed(outcome, new ChatTimings(30, 120, 900, 960, false))));

    MvcResult started =
        mockMvc
            .perform(
                post("/chat/stream")
                    .with(user(principal()))
                    .with(csrf())
                    .param("question", "How much leave?"))
            .andExpect(request().asyncStarted())
            .andReturn();

    MvcResult completed = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
    String body = completed.getResponse().getContentAsString();

    assertThat(completed.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    assertThat(body)
        .contains("event:panel")
        .contains("event:delta")
        .contains("Eighteen ")
        .contains("days. [S1]")
        // The panel is redrawn once the generation timings are known.
        .contains("First token")
        .contains("Answerable");
    // Exactly two panel frames: one after retrieval, one on completion.
    assertThat(body.split("event:panel", -1)).hasSize(3);
  }

  @Test
  @DisplayName("a retrieval failure streams an error event instead of an HTTP error")
  void streamingReportsFailure() throws Exception {
    when(chat.stream(any(), any())).thenReturn(Flux.just(new ChatEvent.Failed("Retrieval failed.")));

    MvcResult started =
        mockMvc
            .perform(
                post("/chat/stream")
                    .with(user(principal()))
                    .with(csrf())
                    .param("question", "Anything"))
            .andExpect(request().asyncStarted())
            .andReturn();

    String body = mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    assertThat(body).contains("event:error").contains("Retrieval failed.");
  }

  @Test
  @DisplayName("the controller streams the retrieval panel before the first token")
  void controllerStreamsRetrievalFirst() {
    RetrievalOutcome outcome = answerableOutcome("How much leave?");
    when(chat.stream(any(), any()))
        .thenReturn(
            Flux.just(
                new ChatEvent.Retrieved(outcome, 30),
                new ChatEvent.Delta("ok"),
                new ChatEvent.Completed(outcome, new ChatTimings(30, 5, 40, 75, false))));

    List<String> events =
        controller
            .stream(CidContext.of(cidIdOf(dataSource, EMAIL), userIdOf(dataSource, EMAIL), Role.CID_ADMIN), "How much leave?")
            .map(event -> event.event() + ":" + event.data())
            .collectList()
            .block();

    assertThat(events).isNotNull().hasSize(3);
    assertThat(events.get(0)).startsWith("panel:").contains("Stage timings").contains("streaming").doesNotContain("First token");
    assertThat(events.get(1)).startsWith("delta:").contains("ok");
    assertThat(events.get(2)).startsWith("panel:").contains("First token");
  }

  // --- fixtures -------------------------------------------------------------

  private AppUserPrincipal principal() {
    UUID cidId = cidIdOf(dataSource, EMAIL);
    UUID userId = userIdOf(dataSource, EMAIL);
    return AppUserPrincipal.from(
        new User(userId, cidId, EMAIL, "hash-not-used", Role.CID_ADMIN, true));
  }

  private static RetrievalOutcome answerableOutcome(String question) {
    UUID documentId = UUID.randomUUID();
    UUID selectedChunk = UUID.randomUUID();
    UUID droppedChunk = UUID.randomUUID();

    RetrievedChunk chunk =
        new RetrievedChunk(
            "S1",
            selectedChunk,
            documentId,
            1,
            "LEAVE",
            "Employee Handbook",
            "Employees accrue eighteen days of annual leave.",
            0.910,
            5.420,
            0.995);

    RetrievalTrace.CandidateTrace selected =
        new RetrievalTrace.CandidateTrace(
            selectedChunk, documentId, "Employee Handbook", 0.910, 5.420, 1, 1, true, null);
    RetrievalTrace.CandidateTrace dropped =
        new RetrievalTrace.CandidateTrace(
            droppedChunk, documentId, "Employee Handbook", 0.420, -1.200, 2, 2, false, "not selected");

    RetrievalTrace trace =
        new RetrievalTrace(
            question,
            "answer",
            "best reranker score 5.420 on 1 of 2 chunks",
            2,
            2,
            1,
            5.420,
            0.35,
            0.15,
            "all-MiniLM-L6-v2",
            "ms-marco-MiniLM-L-6-v2",
            12,
            8,
            5,
            3,
            List.of(selected, dropped),
            List.of());

    return new RetrievalOutcome(List.of(chunk), trace, null);
  }

  private static RetrievalOutcome refusedOutcome(String question) {
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
            3,
            4,
            0,
            0,
            List.of(),
            List.of());
    return new RetrievalOutcome(
        List.of(),
        trace,
        "I couldn't find this in your organisation's documents, so I can't answer it.");
  }
}
