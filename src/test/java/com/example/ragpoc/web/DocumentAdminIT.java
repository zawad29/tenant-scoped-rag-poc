package com.example.ragpoc.web;

import static com.example.ragpoc.support.PdfFixtures.writePdf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragpoc.document.DocumentService;
import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.support.PostgresTestSupport;
import com.example.ragpoc.tenant.AppUser;
import com.example.ragpoc.tenant.Role;
import com.example.ragpoc.tenant.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The admin screens over real HTTP, with the two properties that matter most:
 * a tenant administrator sees only their own tenant's documents, and another
 * tenant's document ids cannot be used, replaced or deleted through the URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentAdminIT extends PostgresTestSupport {

  private static Path storageRoot;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerDatasourceProperties(registry);
    registry.add("rag.ingestion.worker-enabled", () -> "false");
    registry.add(
        "rag.storage.local-root",
        () -> {
          try {
            if (storageRoot == null) {
              storageRoot = Files.createTempDirectory("rag-storage-admin-it");
            }
            return storageRoot.toString();
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private DataSource dataSource;
  @Autowired private DocumentService documents;
  @Autowired private com.example.ragpoc.ingest.IngestionService ingestion;
  @Autowired private com.example.ragpoc.ingest.IngestionJobRepository jobs;
  @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

  @TempDir Path tempDir;

  private TenantContext tenantA;
  private TenantContext tenantB;
  private AppUserPrincipal adminA;
  private AppUserPrincipal userA;
  private AppUserPrincipal adminB;

  @BeforeEach
  void setUp() {
    resetDatabase(dataSource);
    UUID adminAId =
        seedTenantWithUser(dataSource, passwordEncoder, "Northwind", "admin@a.test", "pw", "TENANT_ADMIN");
    seedTenantWithUser(dataSource, passwordEncoder, "Northwind", "user@a.test", "pw", "USER");
    UUID adminBId =
        seedTenantWithUser(dataSource, passwordEncoder, "Contoso", "admin@b.test", "pw", "TENANT_ADMIN");

    tenantA = TenantContext.of(tenantIdOf(dataSource, "admin@a.test"), adminAId, Role.TENANT_ADMIN);
    tenantB = TenantContext.of(tenantIdOf(dataSource, "admin@b.test"), adminBId, Role.TENANT_ADMIN);

    adminA = principalFor("admin@a.test", Role.TENANT_ADMIN);
    userA = principalFor("user@a.test", Role.USER);
    adminB = principalFor("admin@b.test", Role.TENANT_ADMIN);
  }

  // Upload, replacement and the CSRF-protected multipart path are covered by
  // AdminUploadOverHttpIT against a real embedded server. MockMvc builds its own
  // filter chain, which excludes the MultipartFilter the upload path depends on,
  // so testing multipart here would either fail for unrelated reasons or — with
  // the framework's csrf() helper — pass while the browser path was broken.
  // What remains here is what MockMvc is genuinely good at: request routing and
  // authorization mapping.

  @Test
  @DisplayName("a tenant admin sees their own documents on the admin page")
  void adminSeesOwnDocuments() throws Exception {
    documents.upload(tenantA, "Northwind Policy", "policy.pdf", pdfFor("Northwind canary content."));

    mockMvc
        .perform(get("/admin/documents").with(user(adminA)))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Northwind Policy")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Northwind")));
  }

  @Test
  @DisplayName("the document list never shows another tenant's documents")
  void listDoesNotLeakOtherTenantsDocuments() throws Exception {
    documents.upload(tenantA, "Northwind Secret Policy", "a.pdf", pdfFor("Northwind only content."));
    documents.upload(tenantB, "Contoso Secret Policy", "b.pdf", pdfFor("Contoso only content."));

    mockMvc
        .perform(get("/admin/documents").with(user(adminA)))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Northwind Secret Policy")))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Contoso"))));

    // The mirror image, so the first assertion cannot pass because the page is empty.
    mockMvc
        .perform(get("/admin/documents").with(user(adminB)))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Contoso Secret Policy")))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Northwind"))));
  }

  @Test
  @DisplayName("the polling fragment is tenant scoped too")
  void rowsFragmentIsTenantScoped() throws Exception {
    documents.upload(tenantA, "Northwind Only", "a.pdf", pdfFor("Only for Northwind."));

    mockMvc
        .perform(get("/admin/documents/rows").with(user(adminB)))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Northwind Only"))));
  }

  @Test
  @DisplayName("a plain user cannot reach document administration")
  void plainUserIsForbidden() throws Exception {
    mockMvc.perform(get("/admin/documents").with(user(userA))).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("another tenant's document cannot be deleted through the URL")
  void deleteAcrossTenantsIsNotFound() throws Exception {
    UUID documentId = documents.upload(tenantA, "Northwind Policy", "a.pdf", pdfFor("Keep me."));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/admin/documents/" + documentId + "/delete")
                .with(user(adminB))
                .with(csrf()))
        .andExpect(status().isNotFound());

    org.assertj.core.api.Assertions.assertThat(documents.require(tenantA, documentId)).isNotNull();
  }

  @Test
  @DisplayName("another tenant's document cannot be downloaded by id")
  void downloadAcrossTenantsIsNotFound() throws Exception {
    // This is the endpoint that hands over the original file, so it is the most
    // direct way to read another organisation's documents if the lookup were
    // unscoped.
    // Ingest first: a document with no active version has nothing to serve, so an
    // un-ingested document would 404 for its owner too and the test would pass
    // for the wrong reason.
    UUID documentId = documents.upload(tenantA, "Northwind Policy", "a.pdf", pdfFor("Secret content."));
    runPendingJobs();

    mockMvc
        .perform(get("/documents/" + documentId).with(user(adminB)))
        .andExpect(status().isNotFound());

    // And the owner can still fetch it.
    mockMvc
        .perform(get("/documents/" + documentId).with(user(adminA)))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF));
  }

  @Test
  @DisplayName("another tenant's rendered page cannot be requested by id")
  void pageRenderAcrossTenantsIsNotFound() throws Exception {
    UUID documentId = documents.upload(tenantA, "Northwind Policy", "a.pdf", pdfFor("Secret content."));
    runPendingJobs();

    mockMvc
        .perform(get("/documents/" + documentId + "/pages/1").with(user(adminB)))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(get("/documents/" + documentId + "/pages/1").with(user(adminA)))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentType(org.springframework.http.MediaType.IMAGE_PNG));
  }

  @Test
  @DisplayName("a page number beyond the document is not found")
  void pageNumberOutOfRangeIsNotFound() throws Exception {
    UUID documentId = documents.upload(tenantA, "Short Doc", "a.pdf", pdfFor("Only one page."));
    runPendingJobs();

    mockMvc
        .perform(get("/documents/" + documentId + "/pages/99").with(user(adminA)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("an unauthenticated request is sent to the login page")
  void anonymousIsRedirected() throws Exception {
    mockMvc.perform(get("/admin/documents")).andExpect(status().is3xxRedirection());
  }

  // --- helpers --------------------------------------------------------------

  /** Claims and processes every queued job, as the background worker would. */
  private void runPendingJobs() {
    for (int guard = 0; guard < 50; guard++) {
      var claimed = jobs.claim(10);
      if (claimed.isEmpty()) {
        return;
      }
      claimed.forEach(ingestion::process);
    }
    throw new IllegalStateException("Ingestion jobs did not drain");
  }

  /**
   * Uploads using the CSRF token scraped from the rendered page.
   *
   * <p>Deliberately not using the test framework's {@code csrf()} helper, which
   * injects a valid token and would therefore pass even while the real form was
   * broken. The token in a multipart POST is the thing that silently fails in a
   * browser, so the test has to go through the same path the browser does.
   */
  private Path pdfFor(String text) throws Exception {
    return writePdf(tempDir, UUID.randomUUID() + ".pdf", List.of(text));
  }

  private void assertThatVersions(UUID documentId, int expected) {
    int count =
        JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM document_version WHERE document_id = :id")
            .param("id", documentId)
            .query(Integer.class)
            .single();
    org.assertj.core.api.Assertions.assertThat(count).isEqualTo(expected);
  }

  private AppUserPrincipal principalFor(String email, Role role) {
    UUID tenantId = tenantIdOf(dataSource, email);
    UUID userId =
        JdbcClient.create(dataSource)
            .sql("SELECT id FROM app_user WHERE lower(email) = lower(:email)")
            .param("email", email)
            .query(UUID.class)
            .single();
    return AppUserPrincipal.from(new AppUser(userId, tenantId, email, "hash", role, true));
  }
}
