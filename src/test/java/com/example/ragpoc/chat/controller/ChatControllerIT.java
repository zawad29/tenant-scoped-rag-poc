package com.example.ragpoc.chat.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.retrieval.model.RetrievalTrace;
import com.example.ragpoc.retrieval.model.RetrievedChunk;
import com.example.ragpoc.retrieval.service.RetrievalService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The retrieval console, with {@link RetrievalService} mocked so the test needs
 * no ONNX models and can assert exactly what the panel renders.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerIT extends IntegrationTestSupport {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private DataSource dataSource;
  @Autowired private PasswordEncoder passwordEncoder;

  @MockitoBean private RetrievalService retrieval;

  private static final String EMAIL = "admin@canary.test";

  @BeforeEach
  void seed() {
    resetDatabase(dataSource);
    seedCidWithUser(dataSource, passwordEncoder, "Canary A", EMAIL, "pw", "CID_ADMIN");
  }

  @Test
  @DisplayName("an anonymous request is redirected to the login page")
  void anonymousIsRedirected() throws Exception {
    mockMvc.perform(get("/chat")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
  }

  @Test
  @DisplayName("the console renders before a question is asked")
  void consoleRenders() throws Exception {
    mockMvc
        .perform(get("/chat").with(user(principal())))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Chat")))
        .andExpect(content().string(containsString("Ask a question to see the retrieved candidates")));
  }

  @Test
  @DisplayName("a question renders the retrieved candidates with ranks and scores")
  void questionRendersRetrievalOutput() throws Exception {
    when(retrieval.retrieve(any(), any())).thenReturn(answerableOutcome("How much leave?"));

    mockMvc
        .perform(post("/chat").with(user(principal())).with(csrf()).param("question", "How much leave?"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("How much leave?")))
        .andExpect(content().string(containsString("Answerable")))
        .andExpect(content().string(containsString("Employee Handbook")))
        // Selected source with its scores.
        .andExpect(content().string(containsString("S1")))
        .andExpect(content().string(containsString("5.420")))
        // Candidate table: dense rank, rerank rank and the dropped reason.
        .andExpect(content().string(containsString("Dense")))
        .andExpect(content().string(containsString("Rerank")))
        .andExpect(content().string(containsString("not selected")));
  }

  @Test
  @DisplayName("a refusal renders the refusal message and the candidates considered")
  void refusalRendersMessage() throws Exception {
    when(retrieval.retrieve(any(), any())).thenReturn(refusedOutcome("Anything about zebras?"));

    mockMvc
        .perform(post("/chat").with(user(principal())).with(csrf()).param("question", "Anything about zebras?"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Refused")))
        .andExpect(content().string(containsString("couldn't find this")));
  }

  // --- fixtures -------------------------------------------------------------

  private AppUserPrincipal principal() {
    UUID cidId = cidIdOf(dataSource, EMAIL);
    UUID userId = userIdOf(dataSource, EMAIL);
    return AppUserPrincipal.from(new User(userId, cidId, EMAIL, "hash-not-used", Role.CID_ADMIN, true));
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
            3,
            3,
            "LEAVE",
            "Employee Handbook",
            "Employees accrue eighteen days of annual leave.",
            0.910,
            5.420,
            0.995);

    RetrievalTrace.CandidateTrace selected =
        new RetrievalTrace.CandidateTrace(
            selectedChunk, documentId, "Employee Handbook", 3, 3, 0.910, 5.420, 1, 1, true, null);
    RetrievalTrace.CandidateTrace dropped =
        new RetrievalTrace.CandidateTrace(
            droppedChunk, documentId, "Employee Handbook", 9, 9, 0.420, -1.200, 2, 2, false, "not selected");

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
            List.of(),
            List.of());
    return new RetrievalOutcome(
        List.of(),
        trace,
        "I couldn't find this in your organisation's documents, so I can't answer it.");
  }
}
