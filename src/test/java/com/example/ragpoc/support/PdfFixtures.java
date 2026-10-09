package com.example.ragpoc.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Builds PDFs for tests.
 *
 * <p>Generated rather than committed as binaries: the content is then visible in
 * the test that uses it, the canary strings cannot drift from their assertions,
 * and there is no opaque fixture to reverse-engineer later.
 *
 * <p>Text is restricted to ASCII because the standard PDF fonts use WinAnsi and
 * throw on anything outside it.
 */
public final class PdfFixtures {

  private PdfFixtures() {}

  /** One entry per page; {@code \n} separates lines within a page. */
  public static Path writePdf(Path directory, String fileName, List<String> pageTexts)
      throws IOException {
    Files.createDirectories(directory);
    Path target = directory.resolve(fileName);

    try (PDDocument document = new PDDocument()) {
      for (String pageText : pageTexts) {
        addPage(document, pageText);
      }
      document.save(target.toFile());
    }
    return target;
  }

  public static Path writeEncryptedPdf(Path directory, String fileName, List<String> pageTexts)
      throws IOException {
    Files.createDirectories(directory);
    Path target = directory.resolve(fileName);

    try (PDDocument document = new PDDocument()) {
      for (String pageText : pageTexts) {
        addPage(document, pageText);
      }
      AccessPermission permission = new AccessPermission();
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner-secret", "user-secret", permission);
      policy.setEncryptionKeyLength(128);
      document.protect(policy);
      document.save(target.toFile());
    }
    return target;
  }

  /** A file that is not a PDF at all, with a misleading .pdf name. */
  public static Path writeNonPdf(Path directory, String fileName) throws IOException {
    Files.createDirectories(directory);
    Path target = directory.resolve(fileName);
    Files.writeString(target, "This is plain text pretending to be a document.", StandardCharsets.UTF_8);
    return target;
  }

  /** A PDF whose pages contain only whitespace, standing in for a scan. */
  public static Path writeEmptyTextPdf(Path directory, String fileName, int pages)
      throws IOException {
    List<String> blank = java.util.Collections.nCopies(pages, "   \n  ");
    return writePdf(directory, fileName, blank);
  }

  /** Bytes that start like a PDF but are not parseable. */
  public static Path writeCorruptPdf(Path directory, String fileName) throws IOException {
    Files.createDirectories(directory);
    Path target = directory.resolve(fileName);
    Files.write(target, "%PDF-1.7\nthis is not a real pdf body".getBytes(StandardCharsets.UTF_8));
    return target;
  }

  private static void addPage(PDDocument document, String pageText) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    document.addPage(page);

    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
      content.beginText();
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
      content.setLeading(15);
      content.newLineAtOffset(50, 780);

      String[] lines = pageText.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (!line.isEmpty()) {
          content.showText(sanitise(line));
        }
        if (i < lines.length - 1) {
          content.newLine();
        }
      }
      content.endText();
    }
  }

  /** Keeps only characters the standard fonts can encode. */
  private static String sanitise(String line) {
    StringBuilder builder = new StringBuilder(line.length());
    for (char c : line.toCharArray()) {
      builder.append(c >= 32 && c <= 126 ? c : ' ');
    }
    return builder.toString();
  }
}
