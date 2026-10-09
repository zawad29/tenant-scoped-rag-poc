package com.example.ragpoc.ingest;

/**
 * A chunk ready to be embedded and stored.
 *
 * @param index position within the document version, starting at zero
 * @param text the clean display text, without the contextual prefix that gets
 *     embedded
 * @param pageStart first page the chunk covers, 1-based
 * @param pageEnd last page the chunk covers, inclusive
 * @param sectionTitle nearest preceding heading, or null when the document has
 *     no detectable structure
 */
public record Chunk(int index, String text, int pageStart, int pageEnd, String sectionTitle) {

  public Chunk {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("chunk text must not be blank");
    }
    if (pageStart < 1 || pageEnd < pageStart) {
      throw new IllegalArgumentException("invalid page range " + pageStart + "-" + pageEnd);
    }
  }

  /** Page range as shown in a citation. */
  public String pageRange() {
    return pageStart == pageEnd ? String.valueOf(pageStart) : pageStart + "-" + pageEnd;
  }
}
