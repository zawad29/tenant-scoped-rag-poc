package com.example.ragpoc.ingest;

import static com.example.ragpoc.support.ModelAssets.embeddingTokenizer;
import static com.example.ragpoc.support.ModelAssets.missingMessage;
import static com.example.ragpoc.support.PdfFixtures.writePdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.ragpoc.adapter.onnx.HfTokenCounter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The end-to-end text pipeline with the real tokenizer and a real PDF.
 *
 * <p>This is where decision D7 stops being an assumption. The chunk that gets
 * embedded is not the stored text; it is {@code doc_title + section_title +
 * text}. If that composite exceeds bge's 512-token window, the tokenizer
 * silently truncates the tail and the end of every chunk becomes invisible to
 * retrieval. Nothing crashes, and no unit test on chunk sizes alone would catch
 * it.
 */
@Tag("onnx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionPipelineContractTest {

  /** bge-base-en-v1.5's configured maximum sequence length. */
  private static final int EMBEDDING_CONTEXT_WINDOW = 512;

  private static final int TARGET_TOKENS = 400;
  private static final int MAX_TOKENS = 480;
  private static final int MIN_TOKENS = 48;
  private static final int OVERLAP_PERCENT = 12;

  private HfTokenCounter tokenCounter;
  private final PdfPageExtractor extractor = new PdfPageExtractor(50L * 1024 * 1024, 500);
  private final TextCleaner cleaner = new TextCleaner();

  @TempDir Path tempDir;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(Files.isReadable(embeddingTokenizer()), missingMessage());
    tokenCounter = new HfTokenCounter(embeddingTokenizer());
  }

  @AfterAll
  void tearDown() {
    if (tokenCounter != null) {
      tokenCounter.close();
    }
  }

  @Test
  @DisplayName("the embedded text of every chunk fits the embedding model's context window")
  void contextualisedChunkFitsTheContextWindow() throws IOException {
    Path pdf = writePdf(tempDir, "handbook.pdf", handbookPages(6));
    String documentTitle = "Employee Handbook 2026 Policies";

    List<Chunk> chunks = runPipeline(documentTitle, pdf);

    assertThat(chunks).as("the pipeline should produce chunks").isNotEmpty();

    for (Chunk chunk : chunks) {
      String embeddedText = contextualPrefix(documentTitle, chunk);
      assertThat(tokenCounter.count(embeddedText))
          .as(
              "chunk %d would be truncated at embedding time (%d tokens including the prefix)",
              chunk.index(), tokenCounter.count(embeddedText))
          .isLessThanOrEqualTo(EMBEDDING_CONTEXT_WINDOW);
    }
  }

  @Test
  @DisplayName("chunks stay within the configured bounds when measured with the real tokenizer")
  void chunksRespectConfiguredBounds() throws IOException {
    Path pdf = writePdf(tempDir, "handbook.pdf", handbookPages(6));

    List<Chunk> chunks = runPipeline("Employee Handbook 2026 Policies", pdf);

    for (Chunk chunk : chunks) {
      assertThat(tokenCounter.count(chunk.text()))
          .as("chunk %d exceeds the configured maximum", chunk.index())
          .isLessThanOrEqualTo(MAX_TOKENS);
    }
  }

  @Test
  @DisplayName("section headings from the document become chunk section titles")
  void headingsBecomeSectionTitles() throws IOException {
    Path pdf = writePdf(tempDir, "sections.pdf", handbookPages(3));

    List<Chunk> chunks = runPipeline("Employee Handbook", pdf);

    List<String> sections = chunks.stream().map(Chunk::sectionTitle).filter(java.util.Objects::nonNull).toList();

    assertThat(sections).isNotEmpty();
    assertThat(sections).anyMatch(s -> s.contains("ANNUAL LEAVE"));
  }

  @Test
  @DisplayName("canary text in the PDF survives into a chunk verbatim")
  void canaryTextSurvivesVerbatim() throws IOException {
    // The isolation suite depends on this: a canary string must be findable in
    // the index exactly as written, or a "no matches" result proves nothing.
    String canary = "Project Zephyr Quokka Protocol";
    Path pdf =
        writePdf(
            tempDir,
            "canary.pdf",
            List.of("Introduction\nThis document describes the " + canary + " in detail."));

    List<Chunk> chunks = runPipeline("Canary Document", pdf);

    assertThat(chunks.stream().anyMatch(c -> c.text().contains(canary)))
        .as("the canary string must appear in at least one chunk")
        .isTrue();
  }

  @Test
  @DisplayName("headers and footers do not reach the embedded text")
  void boilerplateDoesNotReachChunks() throws IOException {
    Path pdf = writePdf(tempDir, "running-heads.pdf", handbookPages(4));

    List<Chunk> chunks = runPipeline("Employee Handbook", pdf);

    for (Chunk chunk : chunks) {
      assertThat(chunk.text()).doesNotContain("Internal Use Only - Acme Corporation");
    }
  }

  // --- helpers --------------------------------------------------------------

  private List<Chunk> runPipeline(String documentTitle, Path pdf) {
    List<RawPage> raw = extractor.extract(pdf);
    List<CleanedPage> cleaned = cleaner.clean(raw);
    DocumentChunker chunker =
        new DocumentChunker(tokenCounter, TARGET_TOKENS, MAX_TOKENS, MIN_TOKENS, OVERLAP_PERCENT);
    return chunker.chunk(documentTitle, cleaned);
  }

  /** Mirrors the contextualisation the ingestion service performs before embedding. */
  private static String contextualPrefix(String documentTitle, Chunk chunk) {
    String section = chunk.sectionTitle() == null ? "" : chunk.sectionTitle() + " ";
    return documentTitle + " " + section + chunk.text();
  }

  /**
   * Builds a multi-page PDF with a running head and foot on every page, several
   * headings, and long prose, so the pipeline meets all three quirks at once.
   */
  private static List<String> handbookPages(int pages) {
    List<String> content = new ArrayList<>(pages);
    for (int page = 1; page <= pages; page++) {
      StringBuilder pageText = new StringBuilder();
      pageText.append("Internal Use Only - Acme Corporation\n");
      pageText.append(page % 2 == 1 ? "ANNUAL LEAVE\n" : "SICKNESS ABSENCE\n");

      for (int paragraph = 0; paragraph < 3; paragraph++) {
        pageText.append(
            "Employees accrue eighteen days of annual leave per calendar year, calculated pro "
                + "rata for part-time staff and rounded up to the nearest half day. ");
        pageText.append(
            "Requests must be submitted through the absence portal at least fourteen days "
                + "before the intended start date. ");
        pageText.append(
            "Where operational needs require it, a manager may decline a request and must "
                + "record the reason in writing. ");
      }
      pageText.append("\nConfidential - Page ").append(page).append(" of ").append(pages);
      content.add(pageText.toString());
    }
    return content;
  }
}
