package com.example.ragpoc.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a cleaned document into chunks for embedding.
 *
 * <p>The strategy is structure-aware recursive splitting, in the order the
 * specification asks for: heading, then paragraph, then sentence. Sentences are
 * the smallest unit, so a chunk never ends mid-sentence unless a single sentence
 * is itself longer than the maximum, in which case it is broken at a word
 * boundary — the only alternative would be handing the embedding model text
 * longer than its context window.
 *
 * <p>Token counts come from the embedding model's own tokenizer, and the
 * configured maximum leaves room for the contextual prefix
 * ({@code doc_title + section_title + text}) that is embedded but not stored
 * (decision D7).
 */
public class DocumentChunker {

  private final TokenCounter tokenCounter;
  private final int targetTokens;
  private final int maxTokens;
  private final int minTokens;
  private final int overlapPercent;

  public DocumentChunker(
      TokenCounter tokenCounter, int targetTokens, int maxTokens, int minTokens, int overlapPercent) {
    if (maxTokens <= 0 || targetTokens <= 0 || targetTokens > maxTokens) {
      throw new IllegalArgumentException(
          "targetTokens must be positive and no greater than maxTokens");
    }
    if (minTokens < 0 || minTokens > targetTokens) {
      throw new IllegalArgumentException("minTokens must be between 0 and targetTokens");
    }
    if (overlapPercent < 0 || overlapPercent >= 100) {
      throw new IllegalArgumentException("overlapPercent must be in [0, 100)");
    }
    this.tokenCounter = tokenCounter;
    this.targetTokens = targetTokens;
    this.maxTokens = maxTokens;
    this.minTokens = minTokens;
    this.overlapPercent = overlapPercent;
  }

  /**
   * Chunks a cleaned document.
   *
   * @param documentTitle kept for diagnostics; it is part of the embedded prefix,
   *     not of the stored chunk text
   */
  public List<Chunk> chunk(String documentTitle, List<CleanedPage> pages) {
    List<Piece> pieces = toPieces(pages);
    if (pieces.isEmpty()) {
      return List.of();
    }

    List<Chunk> chunks = new ArrayList<>();
    int overlapBudget = Math.max(1, maxTokens * overlapPercent / 100);

    int cursor = 0;
    while (cursor < pieces.size()) {
      int start = cursor;

      // Grow the chunk to the target size, never past the maximum. The first
      // piece is always taken, so an oversized piece cannot stall the loop.
      List<Piece> current = new ArrayList<>();
      int tokens = 0;
      while (cursor < pieces.size()) {
        Piece piece = pieces.get(cursor);
        if (!current.isEmpty() && tokens + piece.tokens() > maxTokens) {
          break;
        }
        current.add(piece);
        tokens += piece.tokens();
        cursor++;
        if (tokens >= targetTokens) {
          break;
        }
      }

      // Top up a short chunk rather than emit a stub, unless the document is
      // genuinely at its end.
      while (cursor < pieces.size()
          && tokens < minTokens
          && tokens + pieces.get(cursor).tokens() <= maxTokens) {
        Piece piece = pieces.get(cursor);
        current.add(piece);
        tokens += piece.tokens();
        cursor++;
      }

      chunks.add(toChunk(chunks.size(), current));

      // Carry the tail of this chunk into the next one so a sentence that
      // straddles the boundary is still fully present in one of them.
      int carry = 0;
      int carried = 0;
      while (cursor - 1 - carry >= start + 1 && carried + pieces.get(cursor - 1 - carry).tokens() <= overlapBudget) {
        carried += pieces.get(cursor - 1 - carry).tokens();
        carry++;
      }
      if (carry > 0) {
        cursor -= carry;
      }
    }

    return chunks;
  }

  private Chunk toChunk(int index, List<Piece> pieces) {
    String text = String.join(" ", pieces.stream().map(Piece::text).toList());
    int pageStart = pieces.getFirst().page();
    int pageEnd = pieces.getLast().page();
    String sectionTitle = pieces.getFirst().section();
    if (sectionTitle != null && sectionTitle.equals(text)) {
      // A chunk that is only a heading carries no content to answer from.
      sectionTitle = null;
    }
    return new Chunk(index, text, pageStart, pageEnd, sectionTitle);
  }

  // --- document to pieces ---------------------------------------------------

  private List<Piece> toPieces(List<CleanedPage> pages) {
    List<Piece> pieces = new ArrayList<>();
    String section = null;

    for (CleanedPage page : pages) {
      for (CleanedPage.Block block : page.blocks()) {
        if (block.heading()) {
          section = block.text();
        }
        for (String sentence : SentenceSplitter.split(block.text())) {
          for (String part : splitToFit(sentence)) {
            int tokens = tokenCounter.count(part);
            if (tokens == 0) {
              continue;
            }
            pieces.add(new Piece(part, tokens, page.pageNumber(), section));
          }
        }
      }
    }
    return pieces;
  }

  /**
   * Breaks a single sentence that is longer than the maximum into word-boundary
   * pieces.
   *
   * <p>The common case costs one token count: the whole sentence is measured
   * first and returned unchanged when it fits. Only a genuinely oversized
   * sentence — a long enumerated list extracted as one line, typically — pays
   * for the binary search that finds each cut point.
   */
  private List<String> splitToFit(String sentence) {
    if (tokenCounter.count(sentence) <= maxTokens) {
      return List.of(sentence);
    }

    String[] words = sentence.split("\\s+");
    List<String> parts = new ArrayList<>();
    int start = 0;
    while (start < words.length) {
      int end = largestFittingPrefix(words, start);
      parts.add(String.join(" ", java.util.Arrays.copyOfRange(words, start, end)));
      start = end;
    }
    return parts;
  }

  /** Largest {@code end > start} such that words[start, end) fit in maxTokens. */
  private int largestFittingPrefix(String[] words, int start) {
    int low = start + 1;
    int high = words.length;
    int best = low;
    while (low <= high) {
      int mid = low + (high - low) / 2;
      String candidate = String.join(" ", java.util.Arrays.copyOfRange(words, start, mid));
      if (tokenCounter.count(candidate) <= maxTokens) {
        best = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return best;
  }

  /** A sentence-sized unit with the metadata needed to place it in the document. */
  private record Piece(String text, int tokens, int page, String section) {}
}
