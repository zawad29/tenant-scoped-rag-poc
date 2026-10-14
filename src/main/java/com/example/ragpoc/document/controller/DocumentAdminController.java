package com.example.ragpoc.document.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.document.DocumentSummary;
import com.example.ragpoc.document.service.DocumentService;
import com.example.ragpoc.ingestion.IngestionValidationException;
import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.web.ShellModel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Document administration: upload, replace, delete.
 *
 * <p>Route-level authorization only establishes that the user may administer
 * their own cid's documents. Every id from the browser is resolved through a
 * cid-scoped lookup, and a miss returns 404 rather than 403. Ingestion is
 * synchronous, so a rejection is reported straight back on the redirect.
 */
@Controller
public class DocumentAdminController {

  private final DocumentService documents;
  private final ShellModel shell;

  public DocumentAdminController(DocumentService documents, ShellModel shell) {
    this.documents = documents;
    this.shell = shell;
  }

  @GetMapping("/admin/documents")
  public String page(
      CidContext cid,
      @AuthenticationPrincipal AppUserPrincipal principal,
      CsrfToken csrfToken,
      @ModelAttribute("message") String message,
      @ModelAttribute("error") String error,
      Model model) {
    populate(cid, principal, csrfToken, model);
    model.addAttribute("message", message);
    model.addAttribute("error", error);
    return "admin/documents";
  }

  private void populate(
      CidContext cid,
      AppUserPrincipal principal,
      CsrfToken csrfToken,
      Model model) {
    List<DocumentSummary> rows = documents.list(cid);
    shell.add(model, cid, principal, csrfToken, "documents");
    model.addAttribute("rows", rows);
  }

  @PostMapping("/admin/documents")
  public String upload(
      CidContext cid,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "title", required = false) String title,
      RedirectAttributes redirect)
      throws IOException {

    if (file.isEmpty()) {
      redirect.addFlashAttribute("error", "Choose a PDF file to upload.");
      return "redirect:/admin/documents";
    }

    Path temp = copyToTemp(file);
    try {
      documents.upload(cid, title, safeFileName(file), temp);
      redirect.addFlashAttribute("message", "Uploaded and indexed.");
    } catch (IngestionValidationException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    } finally {
      Files.deleteIfExists(temp);
    }
    return "redirect:/admin/documents";
  }

  @PostMapping("/admin/documents/{documentId}/versions")
  public String replace(
      CidContext cid,
      @PathVariable UUID documentId,
      @RequestParam("file") MultipartFile file,
      RedirectAttributes redirect)
      throws IOException {

    if (file.isEmpty()) {
      redirect.addFlashAttribute("error", "Choose a PDF file to upload.");
      return "redirect:/admin/documents";
    }

    Path temp = copyToTemp(file);
    try {
      var version = documents.replace(cid, documentId, safeFileName(file), temp);
      if (version.isEmpty()) {
        redirect.addFlashAttribute(
            "message", "The uploaded file is identical to the current version; nothing changed.");
      } else {
        redirect.addFlashAttribute("message", "Replaced and indexed.");
      }
    } catch (IngestionValidationException e) {
      redirect.addFlashAttribute("error", e.getMessage());
    } finally {
      Files.deleteIfExists(temp);
    }
    return "redirect:/admin/documents";
  }

  @PostMapping("/admin/documents/{documentId}/delete")
  public String delete(
      CidContext cid, @PathVariable UUID documentId, RedirectAttributes redirect) {
    documents.delete(cid, documentId);
    redirect.addFlashAttribute("message", "Document deleted.");
    return "redirect:/admin/documents";
  }

  /**
   * Streams the upload to a temporary file rather than calling
   * {@code MultipartFile.transferTo}, whose behaviour differs between containers
   * and which fails when the destination already exists.
   */
  private Path copyToTemp(MultipartFile file) throws IOException {
    Path temp = Files.createTempFile("upload-", ".pdf");
    try (InputStream in = file.getInputStream()) {
      Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
    }
    return temp;
  }

  /** Strips any directory component a client may have supplied in the filename. */
  private static String safeFileName(MultipartFile file) {
    String original = file.getOriginalFilename();
    if (original == null || original.isBlank()) {
      return "upload.pdf";
    }
    String name = original.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    return slash >= 0 ? name.substring(slash + 1) : name;
  }
}
