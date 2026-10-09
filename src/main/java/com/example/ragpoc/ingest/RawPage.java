package com.example.ragpoc.ingest;

/** A page as it came out of the PDF, before any cleaning. */
public record RawPage(int pageNumber, String text) {

  public RawPage {
    if (pageNumber < 1) {
      throw new IllegalArgumentException("page numbers are 1-based");
    }
  }

  public boolean blank() {
    return text == null || text.isBlank();
  }
}
