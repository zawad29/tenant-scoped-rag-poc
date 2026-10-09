package com.example.ragpoc.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts text from a PDF, one page at a time.
 *
 * <p>PDFBox directly rather than Spring AI's PDF reader: page numbers, the
 * distinction between "this page has no text" and "this page has not been read
 * yet", and the raw line breaks that header/footer detection depends on are all
 * required, and a thin DocumentReader wrapper discards them.
 */
public class PdfPageExtractor {

  private static final Logger log = LoggerFactory.getLogger(PdfPageExtractor.class);

  /** Leading bytes of every PDF, per the specification. */
  private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

  private static final int MAX_EXTRACTED_CHARS_PER_PAGE = 1_000_000;

  private final long maxBytes;
  private final int maxPages;

  public PdfPageExtractor(long maxBytes, int maxPages) {
    this.maxBytes = maxBytes;
    this.maxPages = maxPages;
  }

  /**
   * Reads every page's text.
   *
   * @throws IngestionValidationException if the file is not a usable text PDF
   */
  public List<RawPage> extract(Path file) {
    validateSize(file);
    validateMagicBytes(file);

    try (PDDocument document = Loader.loadPDF(file.toFile())) {
      if (document.getNumberOfPages() > maxPages) {
        throw new IngestionValidationException(IngestionValidationException.Reason.TOO_MANY_PAGES);
      }

      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);

      List<RawPage> pages = new ArrayList<>(document.getNumberOfPages());
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(document);
        if (text.length() > MAX_EXTRACTED_CHARS_PER_PAGE) {
          text = text.substring(0, MAX_EXTRACTED_CHARS_PER_PAGE);
        }
        pages.add(new RawPage(page, text == null ? "" : text));
      }

      long pagesWithText = pages.stream().filter(page -> !page.blank()).count();
      if (pagesWithText == 0) {
        // O2: a scanned document has no text layer. Refusing is deliberate;
        // OCR is out of scope, and indexing nothing would produce a document
        // that silently answers no questions.
        throw new IngestionValidationException(
            IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT);
      }
      if (pagesWithText < pages.size()) {
        log.debug(
            "PDF has {} of {} pages with no extractable text",
            pages.size() - pagesWithText,
            pages.size());
      }
      return pages;

    } catch (InvalidPasswordException e) {
      throw new IngestionValidationException(
          IngestionValidationException.Reason.ENCRYPTED, e);
    } catch (IOException e) {
      throw new IngestionValidationException(
          IngestionValidationException.Reason.MALFORMED, e);
    }
  }

  private void validateSize(Path file) {
    try {
      if (Files.size(file) > maxBytes) {
        throw new IngestionValidationException(IngestionValidationException.Reason.TOO_LARGE);
      }
    } catch (IOException e) {
      throw new IngestionValidationException(
          IngestionValidationException.Reason.MALFORMED, e);
    }
  }

  /**
   * Checks the magic bytes.
   *
   * <p>Content type and file extension are both attacker-controlled, so neither
   * is trusted. Parsing would reject most non-PDFs anyway, but failing here
   * gives a clearer error and avoids handing arbitrary bytes to the parser.
   */
  private void validateMagicBytes(Path file) {
    try (var in = Files.newInputStream(file)) {
      byte[] head = in.readNBytes(PDF_MAGIC.length);
      if (head.length < PDF_MAGIC.length || !java.util.Arrays.equals(head, PDF_MAGIC)) {
        throw new IngestionValidationException(IngestionValidationException.Reason.NOT_A_PDF);
      }
    } catch (IOException e) {
      throw new IngestionValidationException(
          IngestionValidationException.Reason.MALFORMED, e);
    }
  }
}
