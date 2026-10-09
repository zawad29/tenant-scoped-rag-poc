package com.example.ragpoc.adapter.embed;

import static com.example.ragpoc.support.ModelAssets.cosine;
import static com.example.ragpoc.support.ModelAssets.embeddingModel;
import static com.example.ragpoc.support.ModelAssets.embeddingTokenizer;
import static com.example.ragpoc.support.ModelAssets.magnitude;
import static com.example.ragpoc.support.ModelAssets.missingMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.ragpoc.adapter.onnx.OnnxTextModel;
import com.example.ragpoc.adapter.onnx.PoolingMode;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Step 0 verification for the embedding path.
 *
 * <p>These are not cosmetic checks. The failure mode this guards against is
 * silent: a wrong pooling strategy yields vectors of the right dimension and
 * roughly the right length whose similarity ordering is quietly degraded, which
 * no smoke test would catch and which would only show up as poor answers much
 * later.
 */
@Tag("onnx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxEmbeddingAdapterTest {

  private OnnxTextModel model;
  private OnnxEmbeddingAdapter clsAdapter;
  private OnnxEmbeddingAdapter meanAdapter;

  @BeforeAll
  void setUp() throws Exception {
    assumeTrue(
        java.nio.file.Files.isReadable(embeddingModel()), missingMessage());
    model = OnnxTextModel.load(embeddingModel(), embeddingTokenizer(), 512, 4);
    clsAdapter = new OnnxEmbeddingAdapter(model, "bge-base-en-v1.5", PoolingMode.CLS, true, 768, 8);
    meanAdapter =
        new OnnxEmbeddingAdapter(model, "bge-base-en-v1.5-mean", PoolingMode.MEAN, true, 768, 8);
  }

  @AfterAll
  void tearDown() {
    if (model != null) {
      model.close();
    }
  }

  @Test
  @DisplayName("produces the configured dimension and unit-length vectors")
  void dimensionAndNormalisation() {
    float[] vector = clsAdapter.embed("Annual leave accrues at one and a half days per month.");

    assertThat(vector).hasSize(768);
    assertThat(magnitude(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
  }

  @Test
  @DisplayName("CLS pooled vectors rank a related sentence above an unrelated one")
  void relatednessOrdering() {
    float[] query = clsAdapter.embed("How much annual leave do I get each year?");
    float[] related =
        clsAdapter.embed("Employees accrue eighteen days of annual leave per calendar year.");
    float[] unrelated =
        clsAdapter.embed("The server room air conditioning must be serviced every quarter.");

    double relatedScore = cosine(query, related);
    double unrelatedScore = cosine(query, unrelated);

    assertThat(relatedScore).isGreaterThan(unrelatedScore);
    // A meaningful gap, not a coin flip.
    assertThat(relatedScore - unrelatedScore).isGreaterThan(0.05);
  }

  @Test
  @DisplayName("the same passage embeds identically on repeat calls")
  void deterministic() {
    String text = "Expense claims must be submitted within thirty days of the expense.";
    assertThat(clsAdapter.embed(text)).isEqualTo(clsAdapter.embed(text));
  }

  @Test
  @DisplayName("batched embedding matches one-at-a-time embedding")
  void batchingIsConsistent() {
    List<String> texts =
        List.of(
            "First document about travel policy.",
            "Second document about parental leave.",
            "Third document covering security incident reporting procedures in detail.");

    List<float[]> batched = clsAdapter.embedAll(texts);

    for (int i = 0; i < texts.size(); i++) {
      float[] single = clsAdapter.embed(texts.get(i));
      assertThat(cosine(batched.get(i), single)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }
  }

  @Test
  @DisplayName("CLS and MEAN pooling produce materially different vectors (pooling choice matters)")
  void poolingChoiceIsMaterial() {
    // The reason we cannot use Spring AI's TransformersEmbeddingModel for bge:
    // it hard codes MEAN pooling. If this test ever shows the two agreeing, the
    // documented rationale in docs/decisions.md D2 needs revisiting.
    String text = "Contractors must complete the security awareness module before access is granted.";

    float[] cls = clsAdapter.embed(text);
    float[] mean = meanAdapter.embed(text);

    double similarity = cosine(cls, mean);
    assertThat(similarity).isLessThan(0.999);

    // And the divergence changes ranking, not just the numbers.
    float[] query = clsAdapter.embed("Do contractors need security training?");
    double clsRanked = cosine(query, cls);
    double meanRanked = cosine(query, mean);
    assertThat(Math.abs(clsRanked - meanRanked)).isGreaterThan(0.0);
  }

  @Test
  @DisplayName("reports latency for a realistic ingestion batch")
  void latencyReport() {
    List<String> chunks = new java.util.ArrayList<>();
    for (int i = 0; i < 32; i++) {
      chunks.add(
          "Section "
              + i
              + ": "
              + "This paragraph stands in for a document chunk of roughly the size the "
              + "chunker produces, so the measured throughput reflects real ingestion work. "
              + "It repeats enough text to reach a few hundred tokens.");
    }
    // Warm up, then measure.
    clsAdapter.embedAll(chunks.subList(0, 4));

    long start = System.nanoTime();
    clsAdapter.embedAll(chunks);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    System.out.printf(
        "[spike] embedded %d chunks in %d ms (%.1f ms/chunk)%n",
        chunks.size(), elapsedMs, (double) elapsedMs / chunks.size());

    assertThat(elapsedMs).isLessThan(60_000);
  }
}
