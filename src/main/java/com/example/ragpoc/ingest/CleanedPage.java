package com.example.ragpoc.ingest;

import java.util.List;

/** A page after cleaning, split into paragraph-level blocks. */
public record CleanedPage(int pageNumber, List<Block> blocks) {

  public CleanedPage {
    blocks = List.copyOf(blocks);
  }

  /**
   * A paragraph or heading.
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

  public boolean empty() {
    return blocks.isEmpty();
  }
}
