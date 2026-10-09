package com.example.ragpoc.ingest;

import static com.example.ragpoc.support.PdfFixtures.writeCorruptPdf;
import static com.example.ragpoc.support.PdfFixtures.writeEmptyTextPdf;
import static com.example.ragpoc.support.PdfFixtures.writeEncryptedPdf;
import static com.example.ragpoc.support.PdfFixtures.writeNonPdf;
import static com.example.ragpoc.support.PdfFixtures.writePdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rejection behaviour and page-aware extraction. */
class PdfPageExtractorTest {

  private static final long GENEROUS_SIZE = 50L * 1024 * 1024;
  private static final int GENEROUS_PAGES = 500;

  @TempDir Path tempDir;

  private final PdfPageExtractor extractor = new PdfPageExtractor(GENEROUS_SIZE, GENEROUS_PAGES);

  @Test
  @DisplayName("extracts text per page, preserving page numbers and order")
  void extractsPerPage() throws IOException {
    Path pdf =
        writePdf(
            tempDir,
            "policy.pdf",
            List.of("Page one content about leave.", "Page two content about pay.", "Page three."));

    List<RawPage> pages = extractor.extract(pdf);

    assertThat(pages).hasSize(3);
    assertThat(pages.get(0).pageNumber()).isEqualTo(1);
    assertThat(pages.get(0).text()).contains("Page one content about leave.");
    assertThat(pages.get(1).text()).contains("Page two content about pay.");
    assertThat(pages.get(2).pageNumber()).isEqualTo(3);
  }

  @Test
  @DisplayName("rejects a file that is not a PDF, whatever its extension claims")
  void rejectsNonPdf() throws IOException {
    Path notPdf = writeNonPdf(tempDir, "pretending.pdf");

    assertThatThrownBy(() -> extractor.extract(notPdf))
        .isInstanceOf(IngestionValidationException.class)
        .extracting(e -> ((IngestionValidationException) e).reason())
        .isEqualTo(IngestionValidationException.Reason.NOT_A_PDF);
  }

  @Test
  @DisplayName("rejects a password-protected PDF")
  void rejectsEncryptedPdf() throws IOException {
    Path encrypted = writeEncryptedPdf(tempDir, "secret.pdf", List.of("Confidential content."));

    assertThatThrownBy(() -> extractor.extract(encrypted))
        .isInstanceOf(IngestionValidationException.class)
        .extracting(e -> ((IngestionValidationException) e).reason())
        .isEqualTo(IngestionValidationException.Reason.ENCRYPTED);
  }

  @Test
  @DisplayName("rejects a corrupt PDF that passes the magic-byte check")
  void rejectsCorruptPdf() throws IOException {
    Path corrupt = writeCorruptPdf(tempDir, "corrupt.pdf");

    assertThatThrownBy(() -> extractor.extract(corrupt))
        .isInstanceOf(IngestionValidationException.class)
        .extracting(e -> ((IngestionValidationException) e).reason())
        .isEqualTo(IngestionValidationException.Reason.MALFORMED);
  }

  @Test
  @DisplayName("rejects a document with more pages than allowed")
  void rejectsTooManyPages() throws IOException {
    Path pdf = writePdf(tempDir, "long.pdf", List.of("One.", "Two.", "Three."));
    PdfPageExtractor strict = new PdfPageExtractor(GENEROUS_SIZE, 2);

    assertThatThrownBy(() -> strict.extract(pdf))
        .isInstanceOf(IngestionValidationException.class)
        .extracting(e -> ((IngestionValidationException) e).reason())
        .isEqualTo(IngestionValidationException.Reason.TOO_MANY_PAGES);
  }

  @Test
  @DisplayName("rejects a file larger than allowed")
  void rejectsTooLarge() throws IOException {
    Path pdf = writePdf(tempDir, "big.pdf", List.of("Some content that makes the file non-trivial."));
    PdfPageExtractor strict = new PdfPageExtractor(64, GENEROUS_PAGES);

    assertThatThrownBy(() -> strict.extract(pdf))
        .isInstanceOf(IngestionValidationException.class)
        .extracting(e -> ((IngestionValidationException) e).reason())
        .isEqualTo(IngestionValidationException.Reason.TOO_LARGE);
  }

  @Test
  @DisplayName("rejects a PDF with no text layer, as a scanned document has none (O2)")
  void rejectsPdfWithoutExtractableText() throws IOException {
    Path scanLike = writeEmptyTextPdf(tempDir, "scan.pdf", 3);

    assertThatThrownBy(() -> extractor.extract(scanLike))
        .isInstanceOf(IngestionValidationException.class)
        .extracting(e -> ((IngestionValidationException) e).reason())
        .isEqualTo(IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT);
  }

  @Test
  @DisplayName("every rejection carries a message an administrator can act on")
  void rejectionsCarryUsefulMessages() {
    for (IngestionValidationException.Reason reason :
        IngestionValidationException.Reason.values()) {
      assertThat(reason.message()).isNotBlank();
      assertThat(new IngestionValidationException(reason).getMessage()).isNotBlank();
    }
    // The scanned-document case must say what to do, since it is the most
    // likely rejection an admin will meet.
    assertThat(IngestionValidationException.Reason.NO_EXTRACTABLE_TEXT.message())
        .containsIgnoringCase("scanned");
  }
}
