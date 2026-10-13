package com.example.ragpoc.ingestion.chunk;

/**
 * A paragraph or heading produced by the cleaner.
 *
 * <p>Knowing which block is a heading is what lets the chunker set a section
 * title and prefer to start a new chunk there, instead of cutting a
 * paragraph in half.
 */
public record Block(String text, boolean heading) {

  public Block {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("block text must not be blank");
    }
  }
}
