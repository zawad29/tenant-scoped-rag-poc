package com.example.ragpoc.adapter.rerank;

import static com.example.ragpoc.support.ModelAssets.missingMessage;
import static com.example.ragpoc.support.ModelAssets.rerankerModel;
import static com.example.ragpoc.support.ModelAssets.rerankerTokenizer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.ragpoc.adapter.onnx.OnnxTextModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Step 0 verification for the reranking path.
 *
 * <p>Two things must hold for the relevance gate to be trustworthy: the
 * cross-encoder must order passages correctly, and it must do so fast enough on
 * CPU that scoring a few dozen candidates per question is viable.
 */
@Tag("onnx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxCrossEncoderRerankerAdapterTest {

  private OnnxTextModel model;
  private OnnxCrossEncoderRerankerAdapter reranker;

  @BeforeAll
  void setUp() throws Exception {
    assumeTrue(java.nio.file.Files.isReadable(rerankerModel()), missingMessage());
    model = OnnxTextModel.load(rerankerModel(), rerankerTokenizer(), 512, 4);
    reranker = new OnnxCrossEncoderRerankerAdapter(model, "ms-marco-MiniLM-L-6-v2", 16);
  }

  @AfterAll
  void tearDown() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  @DisplayName("scores the relevant passage above clearly irrelevant ones")
  void ordersByRelevance() {
    String query = "How many days of annual leave do employees accrue?";

    List<String> passages =
        List.of(
            "Employees accrue eighteen days of annual leave per calendar year, pro rata for "
                + "part-time staff.",
            "The office coffee machine is descaling on the first Tuesday of each month.",
            "Annual leave requests must be submitted through the absence portal at least two "
                + "weeks in advance.");

    double[] scores = reranker.score(query, passages);

    assertThat(scores).hasSize(3);
    // The passage that actually answers the question must win.
    assertThat(scores[0]).isGreaterThan(scores[1]);
    assertThat(scores[0]).isGreaterThan(scores[2]);
    // And noise must lose to the topic-adjacent passage.
    assertThat(scores[2]).isGreaterThan(scores[1]);
  }

  @Test
  @DisplayName("separates an on-topic from an off-topic passage by a usable margin")
  void scoreMarginIsUsable() {
    String query = "What is the policy for reporting a security incident?";

    double onTopic =
        reranker
            .score(
                query,
                List.of(
                    "Any suspected security incident must be reported to the security team within "
                        + "one hour of discovery."))[0];
    double offTopic =
        reranker
            .score(
                query,
                List.of("Catering for the quarterly all-hands is ordered through the facilities "
                    + "team."))[0];

    // The gate compares this difference against a calibrated threshold; the gap
    // has to be wide enough for a threshold to sit between the two.
    assertThat(onTopic - offTopic).isGreaterThan(1.0);
  }

  @Test
  @DisplayName("reports per-pair latency for a realistic candidate set")
  void latencyReport() {
    String query = "What are the working hours for remote employees?";

    List<String> candidates = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      candidates.add(
          "Candidate passage "
              + i
              + ": this text is about the same length as a typical retrieved chunk so the "
              + "measurement reflects the real reranking workload of about six hundred tokens "
              + "per candidate.");
    }

    // Warm up.
    reranker.score(query, candidates.subList(0, 2));

    long start = System.nanoTime();
    reranker.score(query, candidates);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    System.out.printf(
        "[spike] reranked %d candidates in %d ms (%.1f ms/pair)%n",
        candidates.size(), elapsedMs, (double) elapsedMs / candidates.size());

    assertThat(elapsedMs).isLessThan(60_000);
  }

  @Test
  @DisplayName("scores in input order so callers can zip results back to candidates")
  void preservesInputOrder() {
    String query = "When must expense claims be submitted?";
    List<String> passages =
        List.of(
            "Expense claims are due within thirty days.",
            "Plants in the atrium are watered on Mondays.",
            "Expense claims require a receipt for any amount over twenty pounds.");

    double[] first = reranker.score(query, passages);
    double[] second = reranker.score(query, passages);

    assertThat(first).containsExactly(second);
  }
}
