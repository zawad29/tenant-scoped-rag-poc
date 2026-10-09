package com.example.ragpoc.web;

import com.example.ragpoc.document.DocumentNotFoundException;
import com.example.ragpoc.document.DocumentRepository;
import com.example.ragpoc.document.DocumentService;
import com.example.ragpoc.ingest.IngestionValidationException;
import com.example.ragpoc.tenant.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Route-level authorization already required {@code TENANT_ADMIN}, but that
 * only establishes the user may administer <em>their own</em> tenant's
 * documents. Every id that arrives from the browser is resolved through a
 * tenant-scoped lookup, and a miss is reported as 404 rather than 403 so that
 * another tenant's document ids cannot be probed for existence.
 */
@Controller
public class DocumentAdminController {

  private static final Logger log = LoggerFactory.getLogger(DocumentAdminController.class);

  private final DocumentService documents;
  private final com.example.ragpoc.tenant.TenantRepository tenants;

  public DocumentAdminController(
      DocumentService documents, com.example.ragpoc.tenant.TenantRepository tenants) {
    this.documents = documents;
    this.tenants = tenants;
  }

  @GetMapping("/admin/documents")
  public String page(
      TenantContext tenant,
      org.springframework.security.web.csrf.CsrfToken csrfToken,
      @ModelAttribute("message") String message,
      @ModelAttribute("error") String error,
      Model model) {

    populateCommon(tenant, csrfToken, model);
    // Flash attributes arrive as model attributes on the redirected request;
    // reading them as parameters and re-adding keeps them declared explicitly
    // rather than relying on the template engine tolerating absent entries.
    model.addAttribute("message", message);
    model.addAttribute("error", error);
    return "admin/documents";
  }

  /**
   * The rows fragment, polled by htmx while anything is still processing.
   *
   * <p>A separate endpoint rather than re-rendering the whole page: the upload
   * form must not be reset by a refresh, or an administrator part-way through
   * typing a title would lose it every couple of seconds.
   */
  @GetMapping("/admin/documents/rows")
  public String rows(
      TenantContext tenant,
      org.springframework.security.web.csrf.CsrfToken csrfToken,
      Model model) {
    List<DocumentRepository.DocumentSummary> rows = documents.list(tenant);
    model.addAttribute("rows", rows);
    model.addAttribute(
        "anyInProgress", rows.stream().anyMatch(DocumentRepository.DocumentSummary::inProgress));
    // The fragment contains forms, so it needs the token too.
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
    model.addAttribute("csrfToken", csrfToken.getToken());
    return "admin/documentRows";
  }

  private void populateCommon(
      TenantContext tenant, org.springframework.security.web.csrf.CsrfToken csrfToken, Model model) {
    model.addAttribute(
        "tenantName",
        tenants.findOwn(tenant.tenantId()).map(com.example.ragpoc.tenant.Tenant::name).orElse("Your organisation"));
    model.addAttribute("csrfToken", csrfToken.getToken());
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());

    List<DocumentRepository.DocumentSummary> rows = documents.list(tenant);
    model.addAttribute("rows", rows);
    model.addAttribute(
        "anyInProgress", rows.stream().anyMatch(DocumentRepository.DocumentSummary::inProgress));
  }

  @PostMapping("/admin/documents")
  public String upload(
      TenantContext tenant,
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
      documents.upload(tenant, title, safeFileName(file), temp);
      redirect.addFlashAttribute("message", "Uploaded. Ingestion has been queued.");
    } finally {
      Files.deleteIfExists(temp);
    }
    return "redirect:/admin/documents";
  }

  @PostMapping("/admin/documents/{documentId}/versions")
  public String replace(
      TenantContext tenant,
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
      var version = documents.replace(tenant, documentId, safeFileName(file), temp);
      if (version.isEmpty()) {
        redirect.addFlashAttribute(
            "message", "The uploaded file is identical to the current version; nothing changed.");
      } else {
        redirect.addFlashAttribute(
            "message", "New version queued. The current version stays answerable until it is ready.");
      }
    } finally {
      Files.deleteIfExists(temp);
    }
    return "redirect:/admin/documents";
  }

  @PostMapping("/admin/documents/{documentId}/delete")
  public String delete(
      TenantContext tenant, @PathVariable UUID documentId, RedirectAttributes redirect) {
    documents.delete(tenant, documentId);
    redirect.addFlashAttribute("message", "Document deleted.");
    return "redirect:/admin/documents";
  }

  /**
   * Copies the upload to a temporary file.
   *
   * <p>Streams the content rather than calling {@code MultipartFile.transferTo}.
   * The container's implementation tries to create the destination itself and
   * fails when it already exists, which makes it awkward to combine with a
   * pre-created temp file and differs between containers. Reading the stream
   * works the same in tests and in production.
   *
   * <p>The service layer takes a {@link Path} rather than a
   * {@code MultipartFile} deliberately: ingestion, replacement and deletion are
   * exercised by tests and by tooling without an HTTP request, and a service
   * signature that only accepted a web type would force those callers through
   * the web layer.
   */
  private Path copyToTemp(MultipartFile file) throws IOException {
    Path temp = Files.createTempFile("upload-", ".pdf");
    try (java.io.InputStream in = file.getInputStream()) {
      Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
