package com.example.ragpoc.retrieval.search;

/** Payload keys shared by everything that writes to or reads from the index. */
public final class ChunkMetadata {

  /** Owning cid; the value the isolation filter and the backstop check use. */
  public static final String CID_ID = "cid_id";

  public static final String DOCUMENT_ID = "document_id";
  public static final String VERSION = "version";
  public static final String CHUNK_INDEX = "chunk_index";
  public static final String SECTION_TITLE = "section_title";
  public static final String DOC_TITLE = "doc_title";
  public static final String EMBEDDING_MODEL = "embedding_model";

  private ChunkMetadata() {}
}
