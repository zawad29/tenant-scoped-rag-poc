package com.example.ragpoc.retrieval;

/**
 * Raised when a retrieved chunk belongs to a tenant other than the caller's.
 *
 * <p>This should be unreachable: the adapter builds the tenant filter itself and
 * there is no tenant-free way to search. It exists because metadata filtering is
 * a convention rather than a boundary, and the convention is enforced by code
 * that could be wrong. Reaching this path means the filter failed, so the
 * request must fail loudly instead of the answer quietly containing another
 * organisation's text.
 */
public class TenantMismatchException extends RuntimeException {

  public TenantMismatchException(String message) {
    super(message);
  }
}
