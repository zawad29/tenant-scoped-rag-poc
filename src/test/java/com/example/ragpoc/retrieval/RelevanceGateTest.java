package com.example.ragpoc.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelevanceGateTest {

  private final RelevanceGate gate = new RelevanceGate(0.35, 0.15);

  @Test
  @DisplayName("refuses when the best score is below the threshold")
  void refusesWeakEvidence() {
    RelevanceGate.GateDecision decision = gate.evaluate(new double[] {0.1, 0.05, -2.0});

    assertThat(decision.answer()).isFalse();
    assertThat(decision.reason()).contains("below min-relevance");
    assertThat(decision.keptCount()).isZero();
  }

  @Test
  @DisplayName("answers when the best score clears the threshold")
  void answersStrongEvidence() {
    RelevanceGate.GateDecision decision = gate.evaluate(new double[] {5.0, 1.0, -3.0});

    assertThat(decision.answer()).isTrue();
    assertThat(decision.bestScore()).isEqualTo(5.0);
    // 5.0 and 1.0 clear the per-chunk floor of 0.15; -3.0 does not.
    assertThat(decision.keptCount()).isEqualTo(2);
    assertThat(decision.droppedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("answers exactly at the threshold, so calibration values are inclusive")
  void thresholdIsInclusive() {
    assertThat(gate.evaluate(new double[] {0.35}).answer()).isTrue();
    assertThat(gate.evaluate(new double[] {0.34}).answer()).isFalse();
  }

  @Test
  @DisplayName("refuses when nothing was retrieved")
  void refusesEmptyCandidates() {
    RelevanceGate.GateDecision decision = gate.evaluate(new double[0]);

    assertThat(decision.answer()).isFalse();
    assertThat(decision.reason()).isEqualTo("no candidates retrieved");
  }

  @Test
  @DisplayName("an all-weak set is refused rather than answered from its best chunk")
  void refusesWhenEveryChunkIsWeak() {
    // The failure this prevents: a question with no answer in the corpus still
    // has a nearest neighbour, and the highest of nothing is not evidence.
    RelevanceGate.GateDecision decision = gate.evaluate(new double[] {0.2, 0.1, 0.05});

    assertThat(decision.answer()).isFalse();
    assertThat(decision.reason()).contains("below min-relevance");
  }

  @Test
  @DisplayName("the degraded path without a reranker refuses only when there is nothing at all")
  void withoutRerankerOnlyChecksForCandidates() {
    RelevanceGate.GateDecision decision = gate.evaluateWithoutReranker(3);

    assertThat(decision.answer()).isTrue();
    assertThat(decision.reason()).contains("gate disabled");
    assertThat(gate.evaluateWithoutReranker(0).answer()).isFalse();
  }

  @Test
  @DisplayName("relevance mapping is monotonic and bounded")
  void relevanceMapping() {
    assertThat(RelevanceGate.asRelevance(0)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(RelevanceGate.asRelevance(10)).isGreaterThan(0.99);
    assertThat(RelevanceGate.asRelevance(-10)).isLessThan(0.01);
    assertThat(RelevanceGate.asRelevance(1)).isGreaterThan(RelevanceGate.asRelevance(0));
  }

  @Test
  @DisplayName("rejects a per-chunk floor above the primary threshold")
  void rejectsIncoherentThresholds() {
    // With the floor above the gate, the floor would silently become the real
    // gate and the configured minRelevance would never be consulted.
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new RelevanceGate(0.1, 0.5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minChunkRelevance");
  }
}
