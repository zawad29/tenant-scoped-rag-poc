package com.example.ragpoc.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragpoc.support.TestTokenCounters;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Chunking behaviour, with a word-counting token counter so the arithmetic in
 * the assertions is visible. The real tokenizer's contract is verified
 * separately.
 */
class DocumentChunkerTest {

  private static final int TARGET = 20;
  private static final int MAX = 25;
  private static final int MIN = 5;
  private static final int OVERLAP_PERCENT = 20; // 5 tokens

  private final DocumentChunker chunker =
      new DocumentChunker(TestTokenCounters.words(), TARGET, MAX, MIN, OVERLAP_PERCENT);

  @Test
  @DisplayName("no chunk exceeds the maximum token count")
  void respectsMaximumSize() {
    List<Chunk> chunks = chunker.chunk("Long Policy", List.of(page(1, prose(40, 6))));

    assertThat(chunks).isNotEmpty();
    for (Chunk chunk : chunks) {
      assertThat(words(chunk.text()))
          .as("chunk %d: %s", chunk.index(), chunk.text())
          .isLessThanOrEqualTo(MAX);
    }
  }

  @Test
  @DisplayName("every sentence of the document appears in at least one chunk")
  void coversTheWholeDocument() {
    List<String> sentences = sentences(12, 5);
    List<Chunk> chunks = chunker.chunk("Coverage", List.of(page(1, String.join(" ", sentences))));

    String combined = chunks.stream().map(Chunk::text).collect(Collectors.joining(" "));
    for (String sentence : sentences) {
      assertThat(combined)
          .as("sentence missing from every chunk: %s", sentence)
          .contains(sentence);
    }
  }

  @Test
  @DisplayName("no chunk ends mid-sentence")
  void neverEndsMidSentence() {
    // Every source sentence ends in a period, so every chunk must too. A chunk
    // ending anywhere else means a sentence was cut in half.
    List<Chunk> chunks = chunker.chunk("Boundaries", List.of(page(1, prose(30, 6))));

    for (Chunk chunk : chunks) {
      assertThat(chunk.text().trim())
          .as("chunk %d ended mid-sentence: %s", chunk.index(), chunk.text())
          .endsWith(".");
    }
  }

  @Test
  @DisplayName("consecutive chunks overlap so a boundary-straddling sentence is whole in one of them")
  void overlapsConsecutiveChunks() {
    // Its own configuration: a 40% overlap budget over 3-word sentences, so a
    // whole sentence fits the budget. With the default settings a sentence is
    // larger than the overlap allowance and carrying it would double the chunk.
    DocumentChunker overlapping =
        new DocumentChunker(TestTokenCounters.words(), 12, 15, 3, 40);

    List<Chunk> chunks = overlapping.chunk("Overlap", List.of(page(1, prose(30, 3))));

    assertThat(chunks).hasSizeGreaterThan(1);

    List<String> firstChunkWords = List.of(chunks.get(0).text().split("\\s+"));
    List<String> secondChunkWords = List.of(chunks.get(1).text().split("\\s+"));

    int overlap = longestSharedPrefixAtTail(firstChunkWords, secondChunkWords);
    assertThat(overlap)
        .as("expected the chunks to share trailing context")
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("carries the page range of the sentences it contains")
  void carriesPageRanges() {
    List<CleanedPage> pages =
        List.of(page(1, "First page sentence about leave."), page(2, "Second page sentence about pay."));

    List<Chunk> chunks = chunker.chunk("Pages", pages);

    assertThat(chunks).isNotEmpty();
    assertThat(chunks.getFirst().pageStart()).isEqualTo(1);
    Chunk last = chunks.getLast();
    assertThat(last.pageEnd()).isEqualTo(2);
    assertThat(last.pageStart()).isLessThanOrEqualTo(last.pageEnd());
  }

  @Test
  @DisplayName("sets the section title from the nearest preceding heading")
  void setsSectionTitleFromHeading() {
    CleanedPage page =
        new CleanedPage(
            1,
            List.of(
                new CleanedPage.Block("ANNUAL LEAVE", true),
                new CleanedPage.Block("Employees accrue eighteen days per year.", false)));

    List<Chunk> chunks = chunker.chunk("Titled", List.of(page));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().sectionTitle()).isEqualTo("ANNUAL LEAVE");
  }

  @Test
  @DisplayName("section title is null when the document has no detectable structure")
  void sectionTitleIsNullWithoutHeadings() {
    CleanedPage page = new CleanedPage(1, List.of(new CleanedPage.Block("Bare prose.", false)));

    List<Chunk> chunks = chunker.chunk("Untitled", List.of(page));

    assertThat(chunks.getFirst().sectionTitle()).isNull();
  }

  @Test
  @DisplayName("hard-splits a single sentence longer than the maximum, at word boundaries")
  void hardSplitsOversizedSentence() {
    // No punctuation anywhere, so this cannot be split at sentence boundaries:
    // the only options are to split mid-sentence or to exceed the embedding
    // model's context window.
    String monster = "word ".repeat(120).trim();

    List<Chunk> chunks = chunker.chunk("Monster", List.of(page(1, monster)));

    assertThat(chunks).hasSizeGreaterThan(1);
    for (Chunk chunk : chunks) {
      assertThat(words(chunk.text())).isLessThanOrEqualTo(MAX);
    }

    // Word boundaries preserved: no chunk contains a partial word, and
    // reassembling them reproduces the original text.
    String reassembled =
        chunks.stream().map(Chunk::text).collect(Collectors.joining(" ")).replaceAll("\\s+", " ").trim();
    assertThat(reassembled).isEqualTo(monster);
  }

  @Test
  @DisplayName("does not emit a stub chunk when the tail is shorter than the minimum")
  void avoidsStubChunks() {
    // 18 words then 2 more: the tail must be folded in rather than emitted alone.
    List<Chunk> chunks = chunker.chunk("Tail", List.of(page(1, prose(3, 6))));

    for (Chunk chunk : chunks) {
      assertThat(words(chunk.text())).isGreaterThanOrEqualTo(MIN);
    }
  }

  @Test
  @DisplayName("returns no chunks for a document with no text")
  void returnsNothingForEmptyDocument() {
    List<CleanedPage> pages = List.of(new CleanedPage(1, List.of()));

    assertThat(chunker.chunk("Empty", pages)).isEmpty();
  }

  @Test
  @DisplayName("indices are contiguous from zero")
  void indicesAreContiguous() {
    List<Chunk> chunks = chunker.chunk("Indices", List.of(page(1, prose(30, 6))));

    for (int i = 0; i < chunks.size(); i++) {
      assertThat(chunks.get(i).index()).isEqualTo(i);
    }
  }

  @Test
  @DisplayName("rejects an incoherent configuration")
  void rejectsIncoherentConfiguration() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> new DocumentChunker(TestTokenCounters.words(), 100, 50, 5, 10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> new DocumentChunker(TestTokenCounters.words(), 50, 100, 5, 150)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- helpers --------------------------------------------------------------

  private static CleanedPage page(int number, String text) {
    return new CleanedPage(number, List.of(new CleanedPage.Block(text, false)));
  }

  /** Prose where each sentence has {@code wordsPerSentence} words. */
  private static String prose(int sentenceCount, int wordsPerSentence) {
    return String.join(" ", sentences(sentenceCount, wordsPerSentence));
  }

  private static List<String> sentences(int count, int wordsPerSentence) {
    List<String> sentences = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      StringBuilder sentence = new StringBuilder();
      for (int w = 0; w < wordsPerSentence - 1; w++) {
        sentence.append("word").append(i).append('x').append(w).append(' ');
      }
      sentence.append("end").append(i).append('.');
      sentences.add(sentence.toString());
    }
    return sentences;
  }

  private static int words(String text) {
    return text.trim().split("\\s+").length;
  }

  /** Number of words at the start of {@code b} that also end {@code a}. */
  private static int longestSharedPrefixAtTail(List<String> a, List<String> b) {
    int max = Math.min(a.size(), b.size());
    for (int length = max; length > 0; length--) {
      if (a.subList(a.size() - length, a.size()).equals(b.subList(0, length))) {
        return length;
      }
    }
    return 0;
  }
}
