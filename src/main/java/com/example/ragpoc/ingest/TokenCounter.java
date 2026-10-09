package com.example.ragpoc.ingest;

/**
 * Counts tokens the way the embedding model will.
 *
 * <p>Not characters over four. Chunk sizes are a contract with the embedding
 * model's context window (512 for bge), and that window is measured in the
 * model's own tokens; an estimate that is 30% off would let chunks be silently
 * truncated at embedding time.
 */
public interface TokenCounter {

  int count(String text);
}
