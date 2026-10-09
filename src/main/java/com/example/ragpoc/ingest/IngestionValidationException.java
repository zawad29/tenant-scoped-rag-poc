package com.example.ragpoc.ingest;

/**
 * A document was rejected, with a reason that can be shown to an administrator.
 *
 * <p>Rejections are expected outcomes, not defects: scanned PDFs (O2) and
 * password-protected files are refused deliberately, with a status the admin UI
 * displays, rather than being retried forever or silently indexed as empty.
 */
public class IngestionValidationException extends RuntimeException {

  /** Why a document could not be ingested. */
  public enum Reason {
    NOT_A_PDF("The file is not a PDF."),
    ENCRYPTED("The PDF is password-protected and cannot be read."),
    TOO_LARGE("The file exceeds the maximum permitted size."),
    TOO_MANY_PAGES("The document exceeds the maximum permitted page count."),
    MALFORMED("The PDF is damaged or could not be parsed."),
    NO_EXTRACTABLE_TEXT(
        "No extractable text was found. Scanned documents are not supported; "
            + "upload a PDF that contains a text layer.");

    private final String message;

    Reason(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  private final Reason reason;

  public IngestionValidationException(Reason reason) {
    super(reason.message());
    this.reason = reason;
  }

  public IngestionValidationException(Reason reason, Throwable cause) {
    super(reason.message(), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
