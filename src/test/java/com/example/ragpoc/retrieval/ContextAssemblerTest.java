package com.example.ragpoc.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragpoc.port.SearchHit;
import com.example.ragpoc.support.TestTokenCounters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {

  private final ContextAssembler assembler = new ContextAssembler(TestTokenCounters.words(), 20);

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
                // Same text, different casing and spacing: the overlap between
                // neighbouring chunks produces exactly this.
                candidate("employees   accrue eighteen   days", 4.0),
                candidate("Sickness is recorded separately", 3.0)));

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.dropped()).hasSize(1);
    assertThat(result.dropped().getFirst().reason()).contains("duplicate");
  }

  @Test
  @DisplayName("stops adding once the token budget would be exceeded")
  void respectsTokenBudget() {
    // Budget is 20 words; each passage is 8 words, so only two fit.
    var result =
        assembler.assemble(
            List.of(
                candidate("one two three four five six seven eight", 5.0),
                candidate("nine ten eleven twelve thirteen fourteen fifteen sixteen", 4.0),
                candidate("seventeen eighteen nineteen twenty twentyone twentytwo twentythree twentyfour", 3.0)));

    assertThat(result.chunks()).hasSize(2);
    assertThat(result.dropped()).hasSize(1);
    assertThat(result.dropped().getFirst().reason()).contains("token budget");
  }

  @Test
  @DisplayName("a passage that does not fit is skipped rather than ending assembly")
  void skipsOversizedPassageAndKeepsGoing() {
    // The first passage is too large for the budget; a later, smaller one still
    // fits. Stopping at the first failure would silently discard usable evidence.
    var result =
        assembler.assemble(
            List.of(
                candidate("too big ".repeat(30).trim(), 5.0),
                candidate("small enough passage", 4.0)));

    assertThat(result.chunks()).hasSize(1);
    assertThat(result.chunks().getFirst().text()).isEqualTo("small enough passage");
  }

  @Test
  @DisplayName("carries page range and section title through for citations")
  void carriesCitationMetadata() {
    SearchHit hit =
        new SearchHit(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, 4, 12, 14, "ANNUAL LEAVE",
            "Employee Handbook", "Employees accrue eighteen days", 0.9, SearchHit.Source.DENSE);

    var result = assembler.assemble(List.of(new Candidate(hit, 0.9, 1.2, 0.03, 4.0)));

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
    var result = assembler.assemble(List.of(new Candidate(hit, 0.5, 2.0, 0.02, 3.0)));

    RetrievedChunk chunk = result.chunks().getFirst();
    assertThat(chunk.rerankScore()).isEqualTo(3.0);
    assertThat(chunk.relevance()).isBetween(0.0, 1.0).isGreaterThan(0.5);
    assertThat(chunk.denseScore()).isEqualTo(0.5);
    assertThat(chunk.keywordScore()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("an empty candidate list produces an empty context")
  void handlesEmptyInput() {
    assertThat(assembler.assemble(List.of()).chunks()).isEmpty();
  }

  private static Candidate candidate(String text, double rerankScore) {
    return new Candidate(hit(UUID.randomUUID(), text), 0.5, 1.0, 0.02, rerankScore);
  }

  private static SearchHit hit(UUID chunkId, String text) {
    return new SearchHit(
        chunkId, UUID.randomUUID(), UUID.randomUUID(), 1, 0, 3, 3, null, "Doc", text, 0.5,
        SearchHit.Source.DENSE);
  }
}
