package com.example.ragpoc.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragpoc.port.SearchHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

  private final RrfFusion fusion = new RrfFusion(60);

  @Test
  @DisplayName("a chunk ranked by both retrievers outranks one ranked highly by only one")
  void agreementWins() {
    // chunkBoth is 3rd in both lists; chunkDenseOnly is 1st dense but absent from
    // keywords. RRF should prefer the one both retrievers agree on, which is the
    // whole point of fusing rather than trusting a single ranking.
    SearchHit both = hit("both");
    SearchHit denseOnly = hit("dense-only");
    SearchHit keywordOnly = hit("keyword-only");

    List<SearchHit> dense = List.of(denseOnly, hit("x"), both, hit("y"));
    List<SearchHit> keyword = List.of(hit("z"), keywordOnly, both);

    List<RrfFusion.FusedHit> fused = fusion.fuse(dense, keyword, 10);

    assertThat(fused.getFirst().hit().chunkId()).isEqualTo(both.chunkId());
  }

  @Test
  @DisplayName("chunks appearing in only one list are still included")
  void singleSourceChunksAreKept() {
    SearchHit denseOnly = hit("dense-only");
    SearchHit keywordOnly = hit("keyword-only");

    List<RrfFusion.FusedHit> fused =
        fusion.fuse(List.of(denseOnly), List.of(keywordOnly), 10);

    assertThat(fused).hasSize(2);
    assertThat(fused).extracting(h -> h.hit().chunkId())
        .containsExactlyInAnyOrder(denseOnly.chunkId(), keywordOnly.chunkId());
  }

  @Test
  @DisplayName("the same chunk from both lists is merged, not duplicated")
  void duplicatesAreMerged() {
    SearchHit same = hit("same");

    List<RrfFusion.FusedHit> fused = fusion.fuse(List.of(same), List.of(same), 10);

    assertThat(fused).hasSize(1);
    assertThat(fused.getFirst().denseScore()).isNotNull();
    assertThat(fused.getFirst().keywordScore()).isNotNull();
  }

  @Test
  @DisplayName("scores are recorded separately so a disagreement is visible in the trace")
  void sourceScoresArePreserved() {
    SearchHit denseHit = new SearchHit(hit("a").chunkId(), UUID.randomUUID(), UUID.randomUUID(), 1, 0, 1, 1, null, "Doc", "text", 0.87, SearchHit.Source.DENSE);
    SearchHit keywordHit = new SearchHit(denseHit.chunkId(), denseHit.tenantId(), denseHit.documentId(), 1, 0, 1, 1, null, "Doc", "text", 4.2, SearchHit.Source.KEYWORD);

    List<RrfFusion.FusedHit> fused = fusion.fuse(List.of(denseHit), List.of(keywordHit), 10);

    assertThat(fused.getFirst().denseScore()).isEqualTo(0.87);
    assertThat(fused.getFirst().keywordScore()).isEqualTo(4.2);
  }

  @Test
  @DisplayName("the limit is applied after fusion, not per input list")
  void limitAppliesToFusedOutput() {
    List<SearchHit> dense = List.of(hit("a"), hit("b"), hit("c"));
    List<SearchHit> keyword = List.of(hit("d"), hit("e"));

    assertThat(fusion.fuse(dense, keyword, 3)).hasSize(3);
  }

  @Test
  @DisplayName("ranking is deterministic for equally scored chunks")
  void deterministicTieBreak() {
    // Two identical lists produce identical scores for every chunk; without a
    // stable tie-break the evaluation harness could not reproduce its own runs.
    List<SearchHit> dense = List.of(hit("a"), hit("b"));
    List<SearchHit> keyword = List.of(hit("c"), hit("d"));

    List<UUID> first = fusion.fuse(dense, keyword, 10).stream().map(f -> f.hit().chunkId()).toList();
    List<UUID> second = fusion.fuse(dense, keyword, 10).stream().map(f -> f.hit().chunkId()).toList();

    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("empty inputs produce an empty result")
  void handlesEmptyInputs() {
    assertThat(fusion.fuse(List.of(), List.of(), 10)).isEmpty();
  }

  @Test
  @DisplayName("rejects a meaningless k")
  void rejectsBadK() {
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new RrfFusion(0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SearchHit hit(String label) {
    // Deterministic id from the label, so two calls with the same label refer to
    // the same chunk.
    UUID chunkId = UUID.nameUUIDFromBytes(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new SearchHit(
        chunkId, UUID.randomUUID(), UUID.randomUUID(), 1, 0, 1, 1, null, "Doc " + label, label, 1.0,
        SearchHit.Source.DENSE);
  }
}
