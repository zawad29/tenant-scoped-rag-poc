package com.example.ragpoc.ingestion.chunk;

/**
 * A chunk ready to be embedded and stored.
 *
 * <p>No page numbers: Tika extracts one text stream with no page boundaries, so
 * a citation identifies the document and section rather than a page range.
 *
 * @param index position within the document version, starting at zero
 * @param text the clean display text, without the contextual prefix that gets
 *     embedded
 * @param sectionTitle nearest preceding heading, or null when the document has
 *     no detectable structure
 */
public record Chunk(int index, String text, String sectionTitle) {

  public Chunk {
    if (index < 0) {
      throw new IllegalArgumentException("chunk index must not be negative");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("chunk text must not be blank");
    }
  }
}
