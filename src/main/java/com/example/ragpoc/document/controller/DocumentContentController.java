package com.example.ragpoc.document.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.document.DocumentNotFoundException;
import com.example.ragpoc.document.entity.Document;
import com.example.ragpoc.document.service.DocumentContentService;
import com.example.ragpoc.document.service.DocumentService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Serves the original document and single rendered pages, so a citation can link
 * to the exact page it came from. Both endpoints resolve the document through a
 * cid-scoped lookup, and responses are marked {@code private, no-store}.
 */
@Controller
public class DocumentContentController {

  private static final int PAGE_RENDER_DPI = 110;

  private final DocumentService documents;
  private final DocumentContentService content;

  public DocumentContentController(DocumentService documents, DocumentContentService content) {
    this.documents = documents;
    this.content = content;
  }

  /** Streams the active version of a document as a PDF download. */
  @GetMapping("/documents/{documentId}")
  public ResponseEntity<StreamingResponseBody> download(
      CidContext cid, @PathVariable UUID documentId) {

    Document document = documents.require(cid, documentId);
    Path file = content.activeVersionFile(cid, document);

    StreamingResponseBody body =
        outputStream -> {
          try (var in = Files.newInputStream(file)) {
            in.transferTo(outputStream);
          }
        };

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document.pdf\"")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .contentType(MediaType.APPLICATION_PDF)
        .body(body);
  }

  /** Renders one page as a PNG, for the citation preview. */
  @GetMapping("/documents/{documentId}/pages/{pageNumber}")
  public ResponseEntity<byte[]> page(
      CidContext cid, @PathVariable UUID documentId, @PathVariable int pageNumber) {

    Document document = documents.require(cid, documentId);
    Path file = content.activeVersionFile(cid, document);

    if (pageNumber < 1) {
      throw new DocumentNotFoundException(documentId);
    }

    byte[] png = renderPage(file, pageNumber, document);

    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .contentType(MediaType.IMAGE_PNG)
        .body(png);
  }

  private byte[] renderPage(Path file, int pageNumber, Document document) {
    try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
      if (pageNumber > pdf.getNumberOfPages()) {
        throw new DocumentNotFoundException(document.getId());
      }
      PDFRenderer renderer = new PDFRenderer(pdf);
      BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, PAGE_RENDER_DPI);

      var buffer = new ByteArrayOutputStream();
      writePng(image, buffer);
      return buffer.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Could not render page " + pageNumber, e);
    }
  }

  private static void writePng(BufferedImage image, OutputStream out) throws IOException {
    if (!ImageIO.write(image, "png", out)) {
      throw new IOException("No PNG writer is available");
    }
  }
}
