package com.example.ragpoc.ingestion.tika;

import com.example.ragpoc.ingestion.IngestionValidationException;
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
 * path. What it does not give us is page boundaries: the whole file is one text
 * stream, and there is no page concept downstream either. Citations carry the
 * document and section instead.
 *
 * <p>The formatter is a bean, not built per call: it is immutable, and its
 * settings are tuning knobs rather than per-document state.
 *
 * <p>The validation reasons are preserved as far as Tika allows: size, a missing
 * text layer, encryption and parse failures are distinguishable. Page-count and
 * magic-byte checks no longer apply, because format detection is now Tika's job.
 */
@Slf4j
public final class TikaTextExtractor {

  private final long maxBytes;
  private final ExtractedTextFormatter formatter;

  public TikaTextExtractor(long maxBytes, ExtractedTextFormatter formatter) {
    this.maxBytes = maxBytes;
    this.formatter = formatter;
  }

  /** Reads a file as one text stream. */
  public String extract(Path file) {
    long bytes = validateSize(file);
    log.info("ingest step=tika-read file={} bytes={}", file.getFileName(), bytes);

    List<Document> documents;
    try {
      documents = new TikaDocumentReader(new FileSystemResource(file), formatter).read();
    } catch (RuntimeException e) {
      throw translate(file, e);
    }

    String text = documents.isEmpty() ? "" : documents.getFirst().getText();
    if (text == null || text.isBlank()) {
      log.warn("ingest rejected file={} reason=NO_EXTRACTABLE_TEXT", file.getFileName());
      throw new IngestionValidationException(IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT);
    }
    log.info("ingest step=tika-extracted file={} chars={}", file.getFileName(), text.length());
    return text;
  }

  private IngestionValidationException translate(Path file, RuntimeException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof EncryptedDocumentException) {
        log.warn("ingest rejected file={} reason=ENCRYPTED", file.getFileName());
        return new IngestionValidationException(
            IngestionValidationException.Reason.ENCRYPTED, failure);
      }
    }
    log.warn("ingest rejected file={} reason=MALFORMED: {}", file.getFileName(), failure.toString());
    return new IngestionValidationException(IngestionValidationException.Reason.MALFORMED, failure);
  }

  private long validateSize(Path file) {
    try {
      long size = Files.size(file);
      if (size > maxBytes) {
        log.warn(
            "ingest rejected file={} reason=TOO_LARGE bytes={} maxBytes={}",
            file.getFileName(), size, maxBytes);
        throw new IngestionValidationException(IngestionValidationException.Reason.TOO_LARGE);
      }
      return size;
    } catch (IOException e) {
      throw new IngestionValidationException(IngestionValidationException.Reason.MALFORMED, e);
    }
  }
}
