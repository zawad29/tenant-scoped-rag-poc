package com.example.ragpoc.retrieval.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragpoc.retrieval.model.Candidate;
import com.example.ragpoc.retrieval.model.RetrievedChunk;
import com.example.ragpoc.retrieval.model.SearchHit;
import com.example.ragpoc.retrieval.rerank.ScoringScale;
import com.example.ragpoc.support.TestTokenCounters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {

  private final ContextAssembler assembler =
      new ContextAssembler(TestTokenCounters.words(), 20, ScoringScale.LOGIT);

  @Test
  @DisplayName("assigns stable source ids in relevance order")
  void assignsSourceIds() {
    var result =
        assembler.assemble(
            List.of(candidate("first three words", 5.0), candidate("second passage here", 4.0)));

    assertThat(result.chunks()).extracting(RetrievedChunk::sourceId).containsExactly("S1", "S2");
    assertThat(result.chunks().getFirst().text()).isEqualTo("first three words");
  }

  @Test
  @DisplayName("drops a passage that duplicates one already selected")
  void dropsDuplicates() {
    var result =
        assembler.assemble(
            List.of(
                candidate("Employees accrue eighteen days", 5.0),
                candidate("employees   accrue eighteen   days", 4.0),
                candidate("Sickness is recorded separately", 3.0)));

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.dropped()).hasSize(1);
    assertThat(result.dropped().getFirst().reason()).contains("duplicate");
  }

  @Test
  @DisplayName("stops adding once the token budget would be exceeded")
  void respectsTokenBudget() {
    var result =
        assembler.assemble(
            List.of(
                candidate("one two three four five six seven eight", 5.0),
                candidate("nine ten eleven twelve thirteen fourteen fifteen sixteen", 4.0),
                candidate("seventeen eighteen nineteen twenty twentyone twentytwo twentythree", 3.0)));

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.dropped()).hasSize(1);
    assertThat(result.dropped().getFirst().reason()).contains("token budget");
  }

  @Test
  @DisplayName("a passage that does not fit is skipped rather than ending assembly")
  void skipsOversizedPassageAndKeepsGoing() {
    var result =
        assembler.assemble(
            List.of(candidate("too big ".repeat(30).trim(), 5.0), candidate("small enough passage", 4.0)));

    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().text()).isEqualTo("small enough passage");
  }

  @Test
  @DisplayName("carries page range and section title through for citations")
  void carriesCitationMetadata() {
    SearchHit hit =
        new SearchHit(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, 12, 14, "ANNUAL LEAVE",
            "Employee Handbook", "Employees accrue eighteen days", 0.9);

    var result = assembler.assemble(List.of(new Candidate(hit, 4.0)));

    RetrievedChunk chunk = result.chunks().getFirst();
    assertThat(chunk.pageRange()).isEqualTo("12-14");
    assertThat(chunk.sectionTitle()).isEqualTo("ANNUAL LEAVE");
    assertThat(chunk.docTitle()).isEqualTo("Employee Handbook");
    assertThat(chunk.version()).isEqualTo(2);
  }

  @Test
  @DisplayName("exposes relevance as a bounded display value while keeping the raw score")
  void keepsRawAndDisplayScores() {
    SearchHit hit = hit(UUID.randomUUID(), "some text");
    var result = assembler.assemble(List.of(new Candidate(hit, 3.0)));

    RetrievedChunk chunk = result.chunks().getFirst();
    assertThat(chunk.rerankScore()).isEqualTo(3.0);
    assertThat(chunk.relevance()).isBetween(0.0, 1.0).isGreaterThan(0.5);
    assertThat(chunk.denseScore()).isEqualTo(0.5);
  }

  @Test
  @DisplayName("an empty candidate list produces an empty context")
  void handlesEmptyInput() {
    assertThat(assembler.assemble(List.of()).chunks()).isEmpty();
  }

  private static Candidate candidate(String text, double rerankScore) {
    return new Candidate(hit(UUID.randomUUID(), text), rerankScore);
  }

  private static SearchHit hit(UUID chunkId, String text) {
    return new SearchHit(
        chunkId, UUID.randomUUID(), UUID.randomUUID(), 1, 3, 3, null, "Doc", text, 0.5);
  }
}
