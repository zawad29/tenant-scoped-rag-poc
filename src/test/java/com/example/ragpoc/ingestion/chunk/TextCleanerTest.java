package com.example.ragpoc.ingestion.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cleaning rules, tested on hand-written extracted text rather than through a
 * PDF. What matters here is the text transformation itself, and stating the
 * input literally makes the expected behaviour readable.
 *
 * <p>Tika yields one text stream with no page boundaries, so the input of every
 * case is a single string.
 */
class TextCleanerTest {

  private final TextCleaner cleaner = new TextCleaner();

  @Test
  @DisplayName("returns no blocks for text that is missing or blank")
  void returnsNothingForBlankText() {
    assertThat(cleaner.clean(null)).isEmpty();
    assertThat(cleaner.clean("   \n  ")).isEmpty();
  }

  @Test
  @DisplayName("removes standalone page numbers in their common forms")
  void removesPageNumbers() {
    List<Block> blocks =
        cleaner.clean("- 3 -\nPage 2 of 3\nLeave Policy\nEmployees accrue leave.");

    String all = flatten(blocks);
    assertThat(all).contains("Employees accrue leave.");
    assertThat(all).doesNotContain("Page 2 of 3");
    assertThat(all).doesNotContain("- 3 -");
  }

  @Test
  @DisplayName("keeps a number that is part of a sentence")
  void keepsNumbersInsideSentences() {
    List<Block> blocks = cleaner.clean("Employees accrue 25 days of leave per year.");

    assertThat(flatten(blocks)).contains("Employees accrue 25 days of leave per year.");
  }

  @Test
  @DisplayName("rejoins words hyphenated across a line break")
  void rejoinsHyphenatedWords() {
    List<Block> blocks =
        cleaner.clean("The policy applies to all employ-\nees and contract-\nors without exception.");

    String all = flatten(blocks);
    assertThat(all).contains("employees");
    assertThat(all).contains("contractors");
    // The stray hyphens must not survive into the text the model quotes.
    assertThat(all).doesNotContain("employ-");
    assertThat(all).doesNotContain("contract-");
  }

  @Test
  @DisplayName("reflows hard line breaks inside a paragraph into one block")
  void reflowsParagraphLines() {
    List<Block> blocks =
        cleaner.clean(
            "Employees accrue eighteen days of\nannual leave per calendar year, pro rata for\npart-time staff.");

    assertThat(blocks).hasSize(1);
    assertThat(blocks.getFirst().text())
        .isEqualTo(
            "Employees accrue eighteen days of annual leave per calendar year, pro rata for "
                + "part-time staff.");
  }

  @Test
  @DisplayName("detects headings so chunks can start at section boundaries")
  void detectsHeadings() {
    List<Block> blocks =
        cleaner.clean(
            "ANNUAL LEAVE\nEmployees accrue eighteen days per year.\n3.1 Carry-over\nUp to five days may be carried over.");

    List<String> headings =
        blocks.stream().filter(Block::heading).map(Block::text).toList();

    assertThat(headings).contains("ANNUAL LEAVE", "3.1 Carry-over");
    // The sentence must not be mistaken for a heading.
    assertThat(headings).noneMatch(h -> h.startsWith("Employees accrue"));
  }

  @Test
  @DisplayName("keeps list items as separate blocks")
  void keepsListItemsSeparate() {
    List<Block> blocks =
        cleaner.clean(
            "Eligibility\n- Permanent employees are eligible.\n- Fixed-term staff are eligible after six months.");

    List<String> texts = blocks.stream().map(Block::text).toList();

    assertThat(texts)
        .contains("- Permanent employees are eligible.")
        .contains("- Fixed-term staff are eligible after six months.");
  }

  @Test
  @DisplayName("normalises non-breaking spaces and zero-width characters")
  void normalisesExoticWhitespace() {
    List<Block> blocks = cleaner.clean("Employees\u00a0accrue\u200b leave.");

    assertThat(flatten(blocks)).isEqualTo("Employees accrue leave.");
  }

  @Test
  @DisplayName("treats a heading with a trailing colon as content, not a heading")
  void doesNotTreatColonLineAsHeading() {
    // "Note:" is a label, but the text that follows belongs with it; splitting
    // there would separate a warning from what it warns about.
    List<Block> blocks = cleaner.clean("This policy applies to the following grades:");

    assertThat(blocks).hasSize(1);
    assertThat(blocks.getFirst().heading()).isFalse();
  }

  // --- helpers --------------------------------------------------------------

  private static String flatten(List<Block> blocks) {
    return blocks.stream()
        .map(Block::text)
        .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
  }
}
