package com.example.ragpoc.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cleaning rules, tested on hand-written raw page text rather than through a
 * PDF. The PDF path is covered separately; what matters here is the text
 * transformation itself, and stating the input literally makes the expected
 * behaviour readable.
 */
class TextCleanerTest {

  private final TextCleaner cleaner = new TextCleaner();

  @Test
  @DisplayName("strips a running header and footer that repeat across pages")
  void stripsRepeatedHeaderAndFooter() {
    List<RawPage> pages =
        List.of(
            page(1, "ACME Corporation - Internal Use Only\nLeave Policy\nEmployees accrue leave.\nConfidential - Page 1"),
            page(2, "ACME Corporation - Internal Use Only\nCarry-over\nUp to five days carry over.\nConfidential - Page 2"),
            page(3, "ACME Corporation - Internal Use Only\nSickness\nSickness is recorded separately.\nConfidential - Page 3"));

    List<CleanedPage> cleaned = cleaner.clean(pages);

    String all = flatten(cleaned);
    assertThat(all).doesNotContain("ACME Corporation - Internal Use Only");
    assertThat(all).doesNotContain("Confidential");
    // Real content survives.
    assertThat(all).contains("Employees accrue leave.");
    assertThat(all).contains("Up to five days carry over.");
    assertThat(all).contains("Sickness is recorded separately.");
  }

  @Test
  @DisplayName("keeps a line that appears once, even at the top of a page")
  void keepsNonRepeatedEdgeLines() {
    List<RawPage> pages =
        List.of(
            page(1, "ACME Corporation - Internal Use Only\nLeave Policy\nEmployees accrue leave.\nPage 1"),
            page(2, "ACME Corporation - Internal Use Only\nCarry-over\nUp to five days carry over.\nPage 2"),
            page(3, "ACME Corporation - Internal Use Only\nUnique Heading Here\nSomething specific to page three.\nPage 3"));

    String all = flatten(cleaner.clean(pages));

    assertThat(all).contains("Unique Heading Here");
    assertThat(all).contains("Something specific to page three.");
    assertThat(all).doesNotContain("ACME Corporation");
  }

  @Test
  @DisplayName("does not strip anything from a two-page document")
  void doesNotStripFromVeryShortDocuments() {
    // With two pages, "repeated" is indistinguishable from coincidence, and
    // guessing wrong would delete real content.
    List<RawPage> pages =
        List.of(
            page(1, "Shared Line\nFirst page body text."),
            page(2, "Shared Line\nSecond page body text."));

    String all = flatten(cleaner.clean(pages));

    assertThat(all).contains("Shared Line");
  }

  @Test
  @DisplayName("removes standalone page numbers in their common forms")
  void removesPageNumbers() {
    List<RawPage> pages =
        List.of(
            page(1, "Leave Policy\nEmployees accrue leave.\n1"),
            page(2, "Carry-over\nUp to five days carry over.\nPage 2 of 3"),
            page(3, "Sickness\nSickness is recorded separately.\n- 3 -"));

    String all = flatten(cleaner.clean(pages));

    assertThat(all).contains("Employees accrue leave.");
    assertThat(all).doesNotContain("Page 2 of 3");
    assertThat(all).doesNotContain("- 3 -");
  }

  @Test
  @DisplayName("keeps a number that is part of a sentence")
  void keepsNumbersInsideSentences() {
    List<RawPage> pages = List.of(page(1, "Employees accrue 25 days of leave per year."));

    String all = flatten(cleaner.clean(pages));

    assertThat(all).contains("Employees accrue 25 days of leave per year.");
  }

  @Test
  @DisplayName("rejoins words hyphenated across a line break")
  void rejoinsHyphenatedWords() {
    List<RawPage> pages =
        List.of(page(1, "The policy applies to all employ-\nees and contract-\nors without exception."));

    String all = flatten(cleaner.clean(pages));

    assertThat(all).contains("employees");
    assertThat(all).contains("contractors");
    // The stray hyphens must not survive into the text the model quotes.
    assertThat(all).doesNotContain("employ-");
    assertThat(all).doesNotContain("contract-");
  }

  @Test
  @DisplayName("reflows hard line breaks inside a paragraph into one block")
  void reflowsParagraphLines() {
    List<RawPage> pages =
        List.of(
            page(
                1,
                "Employees accrue eighteen days of\nannual leave per calendar year, pro rata for\npart-time staff."));

    List<CleanedPage> cleaned = cleaner.clean(pages);

    assertThat(cleaned.getFirst().blocks()).hasSize(1);
    assertThat(cleaned.getFirst().blocks().getFirst().text())
        .isEqualTo(
            "Employees accrue eighteen days of annual leave per calendar year, pro rata for "
                + "part-time staff.");
  }

  @Test
  @DisplayName("detects headings so chunks can start at section boundaries")
  void detectsHeadings() {
    List<RawPage> pages =
        List.of(
            page(
                1,
                "ANNUAL LEAVE\nEmployees accrue eighteen days per year.\n3.1 Carry-over\nUp to five days may be carried over."));

    List<CleanedPage> blocks = cleaner.clean(pages);

    List<String> headings =
        blocks.stream()
            .flatMap(p -> p.blocks().stream())
            .filter(CleanedPage.Block::heading)
            .map(CleanedPage.Block::text)
            .toList();

    assertThat(headings).contains("ANNUAL LEAVE", "3.1 Carry-over");
    // The sentence must not be mistaken for a heading.
    assertThat(headings).noneMatch(h -> h.startsWith("Employees accrue"));
  }

  @Test
  @DisplayName("keeps list items as separate blocks")
  void keepsListItemsSeparate() {
    List<RawPage> pages =
        List.of(
            page(
                1,
                "Eligibility\n- Permanent employees are eligible.\n- Fixed-term staff are eligible after six months."));

    List<CleanedPage> cleaned = cleaner.clean(pages);
    List<String> texts = cleaned.getFirst().blocks().stream().map(CleanedPage.Block::text).toList();

    assertThat(texts)
        .contains("- Permanent employees are eligible.")
        .contains("- Fixed-term staff are eligible after six months.");
  }

  @Test
  @DisplayName("normalises non-breaking spaces and zero-width characters")
  void normalisesExoticWhitespace() {
    List<RawPage> pages = List.of(page(1, "Employees\u00a0accrue\u200b leave."));

    String all = flatten(cleaner.clean(pages));

    assertThat(all).isEqualTo("Employees accrue leave.");
  }

  @Test
  @DisplayName("drops pages that contain no text at all")
  void dropsEmptyPages() {
    List<RawPage> pages = List.of(page(1, "Real content here."), page(2, "   \n  "));

    List<CleanedPage> cleaned = cleaner.clean(pages);

    assertThat(cleaned.get(0).blocks()).isNotEmpty();
    assertThat(cleaned.get(1).blocks()).isEmpty();
  }

  @Test
  @DisplayName("treats a heading with a trailing colon as content, not a heading")
  void doesNotTreatColonLineAsHeading() {
    // "Note:" is a label, but the text that follows belongs with it; splitting
    // there would separate a warning from what it warns about.
    List<RawPage> pages = List.of(page(1, "This policy applies to the following grades:"));

    List<CleanedPage> cleaned = cleaner.clean(pages);

    assertThat(cleaned.getFirst().blocks()).hasSize(1);
    assertThat(cleaned.getFirst().blocks().getFirst().heading()).isFalse();
  }

  // --- helpers --------------------------------------------------------------

  private static RawPage page(int number, String text) {
    return new RawPage(number, text);
  }

  private static String flatten(List<CleanedPage> pages) {
    return pages.stream()
        .flatMap(p -> p.blocks().stream())
        .map(CleanedPage.Block::text)
        .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
  }
}
