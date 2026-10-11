package com.example.ragpoc.retrieval;

/**
 * Raised when a retrieved chunk belongs to a cid other than the caller's.
 *
 * <p>This should be unreachable: the store builds the cid filter itself and there
 * is no cid-free way to search. It exists because metadata filtering is a
 * convention enforced by code that could be wrong; reaching this path means the
 * filter failed, so the request must fail loudly.
 */
public class CidMismatchException extends RuntimeException {

  public CidMismatchException(String message) {
    super(message);
  }
}
