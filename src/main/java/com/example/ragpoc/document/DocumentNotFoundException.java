package com.example.ragpoc.document;

import java.util.UUID;

/**
 * Raised when a document is not found <em>for the calling tenant</em>.
 *
 * <p>Deliberately indistinguishable from "does not exist". Controllers map it to
 * 404 rather than 403, because a 403 would confirm that another tenant's
 * document exists at that id — an information leak, however small, and the
 * specification asks that no tenant's names or counts be observable from
 * outside.
 */
public class DocumentNotFoundException extends RuntimeException {

  public DocumentNotFoundException(UUID documentId) {
    super("No such document: " + documentId);
  }
}
