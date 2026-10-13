package com.example.ragpoc.document.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.document.entity.Document;
import com.example.ragpoc.document.service.DocumentContentService;
import com.example.ragpoc.document.service.DocumentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Serves the stored document. The lookup is cid-scoped and the response is
 * marked {@code private, no-store}.
 *
 * <p>There is no page-rendering endpoint: Tika extracts one text stream with no
 * page boundaries, so citations carry the document and section rather than a
 * page number, and nothing needs a single-page preview.
 */
@Controller
public class DocumentContentController {

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
}
