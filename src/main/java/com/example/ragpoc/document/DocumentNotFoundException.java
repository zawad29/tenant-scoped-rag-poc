package com.example.ragpoc.document;

import java.util.UUID;

/**
 * Raised when a document is not found <em>for the calling cid</em>.
 *
 * <p>Deliberately indistinguishable from "does not exist": controllers map it to
 * 404 rather than 403, so another cid's document ids cannot be probed for
 * existence.
 */
public class DocumentNotFoundException extends RuntimeException {

  public DocumentNotFoundException(UUID documentId) {
    super("No such document: " + documentId);
  }
}
