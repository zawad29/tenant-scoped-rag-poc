package com.example.ragpoc.ingestion.tika;

import com.example.ragpoc.ingestion.IngestionValidationException;
import com.example.ragpoc.ingestion.chunk.RawPage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.EncryptedDocumentException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;

/**
 * Extracts plain text from an uploaded file with Spring AI's Apache Tika reader.
 *
 * <p>Tika auto-detects the format, so PDF, DOCX, PPTX and HTML all take the same
 * path. What Tika does not give us is page boundaries, so the whole document is
 * treated as a single page: the downstream cleaner and chunker keep working
 * unchanged, but citations carry the document and section rather than a page
 * number.
 *
 * <p>The validation reasons are preserved as far as Tika allows: size, a missing
 * text layer, encryption and parse failures are distinguishable. Page-count and
 * magic-byte checks no longer apply, because format detection is now Tika's job.
 */
@Slf4j
public final class TikaTextExtractor {

  private final long maxBytes;

  public TikaTextExtractor(long maxBytes) {
    this.maxBytes = maxBytes;
  }

  /** Reads a file as a single page of text. */
  public List<RawPage> extract(Path file) {
    validateSize(file);

    // Keep every line; our own cleaner decides what is boilerplate.
    ExtractedTextFormatter formatter =
        ExtractedTextFormatter.builder()
            .withNumberOfTopTextLinesToDelete(0)
            .withNumberOfBottomTextLinesToDelete(0)
            .build();

    List<Document> documents;
    try {
      documents =
          new TikaDocumentReader(new FileSystemResource(file), formatter).read();
    } catch (RuntimeException e) {
      throw translate(file, e);
    }

    String text = documents.isEmpty() ? "" : documents.getFirst().getText();
    if (text == null || text.isBlank()) {
      throw new IngestionValidationException(IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT);
    }
    return List.of(new RawPage(1, text));
  }

  private IngestionValidationException translate(Path file, RuntimeException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof EncryptedDocumentException) {
        return new IngestionValidationException(
            IngestionValidationException.Reason.ENCRYPTED, failure);
      }
    }
    log.debug("Tika could not parse {}: {}", file, failure.toString());
    return new IngestionValidationException(IngestionValidationException.Reason.MALFORMED, failure);
  }

  private void validateSize(Path file) {
    try {
      if (Files.size(file) > maxBytes) {
        throw new IngestionValidationException(IngestionValidationException.Reason.TOO_LARGE);
      }
    } catch (IOException e) {
      throw new IngestionValidationException(IngestionValidationException.Reason.MALFORMED, e);
    }
  }
}
