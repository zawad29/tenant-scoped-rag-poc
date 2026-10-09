package com.example.ragpoc.web;

import com.example.ragpoc.document.Document;
import com.example.ragpoc.document.DocumentNotFoundException;
import com.example.ragpoc.document.DocumentService;
import com.example.ragpoc.document.DocumentVersion;
import com.example.ragpoc.document.DocumentVersionRepository;
import com.example.ragpoc.port.FileStoragePort;
import com.example.ragpoc.tenant.TenantContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
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
 * to the exact page it came from.
 *
 * <p>Both endpoints resolve the document through a tenant-scoped lookup, so an id
 * belonging to another tenant produces a 404 rather than that tenant's file. The
 * download path is the most direct way to read another organisation's documents
 * if it were wrong, which is why it is covered explicitly by the isolation suite.
 *
 * <p>Responses are marked {@code private, no-store}. The content is one tenant's,
 * and a shared or intermediate cache holding it would be a leak that no access
 * check could prevent.
 */
@Controller
public class DocumentContentController {

  private static final int PAGE_RENDER_DPI = 110;

  private final DocumentService documents;
  private final DocumentVersionRepository versions;
  private final FileStoragePort storage;

  public DocumentContentController(
      DocumentService documents, DocumentVersionRepository versions, FileStoragePort storage) {
    this.documents = documents;
    this.versions = versions;
    this.storage = storage;
  }

  /** Streams the active version of a document as a PDF download. */
  @GetMapping("/documents/{documentId}")
  public ResponseEntity<StreamingResponseBody> download(
      TenantContext tenant, @PathVariable java.util.UUID documentId) {

    Document document = documents.require(tenant, documentId);
    Path file = activeVersionFile(tenant, document);

    StreamingResponseBody body =
        outputStream -> {
          try (var in = java.nio.file.Files.newInputStream(file)) {
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
      TenantContext tenant, @PathVariable java.util.UUID documentId, @PathVariable int pageNumber) {

    Document document = documents.require(tenant, documentId);
    Path file = activeVersionFile(tenant, document);

    if (pageNumber < 1) {
      throw new DocumentNotFoundException(documentId);
    }

    byte[] png = renderPage(file, pageNumber, document);

    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .contentType(MediaType.IMAGE_PNG)
        .body(png);
  }

  private Path activeVersionFile(TenantContext tenant, Document document) {
    // The active version's file, not the newest one: a replacement that is still
    // ingesting must not change what a citation points at.
    int version = document.currentVersion();
    if (version == 0) {
      throw new DocumentNotFoundException(document.id());
    }
    DocumentVersion documentVersion =
        versions
            .find(tenant, document.id(), version)
            .orElseThrow(() -> new DocumentNotFoundException(document.id()));
    return storage.pathOf(tenant, documentVersion.storageKey());
  }

  private byte[] renderPage(Path file, int pageNumber, Document document) {
    try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
      if (pageNumber > pdf.getNumberOfPages()) {
        // Out of range is a 404, and saying so reveals nothing: the page count
        // of a document the caller already owns is not a secret.
        throw new DocumentNotFoundException(document.id());
      }
      PDFRenderer renderer = new PDFRenderer(pdf);
      BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, PAGE_RENDER_DPI);

      var buffer = new java.io.ByteArrayOutputStream();
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

  /** Page count, for callers that need it before rendering. */
  public Optional<Integer> pageCount(TenantContext tenant, java.util.UUID documentId) {
    return documents.findById(tenant, documentId).map(Document::pageCount);
  }
}
