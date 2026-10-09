package com.example.ragpoc.web;

import static com.example.ragpoc.support.PdfFixtures.writePdf;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragpoc.support.PostgresTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 4 moved TestRestTemplate into its own module and package.
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Document administration over a real embedded server, driven the way a browser
 * drives it.
 *
 * <p>This class exists because MockMvc cannot test the thing that matters here.
 * A multipart POST with CSRF protection depends on {@code MultipartFilter} being
 * registered ahead of the security filter chain, and on Spring MVC reusing the
 * parsed request ({@code resolve-lazily}). MockMvc builds its own filter chain
 * and does not include that filter, so a MockMvc test of the upload path either
 * fails for reasons unrelated to the application, or — worse, if the framework's
 * {@code csrf()} helper is used — passes while the real browser path returns 403.
 *
 * <p>It did pass, and the browser path was in fact broken. So the upload and
 * cross-tenant replacement checks live here, against Tomcat, with real form
 * posts, real session cookies and the CSRF token scraped out of the rendered
 * HTML exactly as a browser would obtain it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Boot 4 requires opting in to the autoconfigured test client.
@AutoConfigureTestRestTemplate
class AdminUploadOverHttpIT extends PostgresTestSupport {

  private static final Pattern CSRF_FIELD =
      Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

  private static final String PASSWORD = "correct-horse-battery-staple";

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
              storageRoot = Files.createTempDirectory("rag-storage-http-it");
            }
            return storageRoot.toString();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /**
   * Cookied client: the session from the login POST must be carried forward.
   *
   * <p>Built manually so cookies are enabled, then pointed at the running server
   * using the root URI of Boot's autoconfigured client — a hand-built
   * {@code TestRestTemplate} has no base URL, and relative paths fail with "URI
   * with undefined scheme".
   */
  private final TestRestTemplate http =
      new TestRestTemplate(TestRestTemplate.HttpClientOption.ENABLE_COOKIES);

  @Autowired private TestRestTemplate autoconfiguredClient;
  @Autowired private DataSource dataSource;
  @Autowired private PasswordEncoder passwordEncoder;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    // Boot 4's TestRestTemplate has no setRootUri; a base-URI template handler
    // gives the hand-built client the running server's address.
    http.setUriTemplateHandler(
        new org.springframework.web.util.DefaultUriBuilderFactory(autoconfiguredClient.getRootUri()));
    resetDatabase(dataSource);
    seedTenantWithUser(dataSource, passwordEncoder, "Northwind", "admin@a.test", PASSWORD, "TENANT_ADMIN");
    seedTenantWithUser(dataSource, passwordEncoder, "Contoso", "admin@b.test", PASSWORD, "TENANT_ADMIN");
  }

  @Test
  @DisplayName("a browser-like login, then a multipart upload with the form's CSRF token, succeeds")
  void uploadThroughTheRealBrowserFlow() throws IOException {
    signIn("admin@a.test");

    ResponseEntity<String> page = http.getForEntity("/admin/documents", String.class);
    assertThat(page.getStatusCode().is2xxSuccessful()).isTrue();

    String token = csrfTokenFrom(page.getBody());
    Path pdf = writePdf(tempDir, "over-http.pdf", List.of("Uploaded over real HTTP with a token."));

    ResponseEntity<String> response = uploadMultipart("/admin/documents", token, "Uploaded Over Http", pdf);

    // Either a redirect (redirects not followed) or the redirect target; either
    // way the assertion that matters is that the document was accepted.
    assertThat(response.getStatusCode().isError())
        .as("upload failed with %s: %s", response.getStatusCode(), response.getBody())
        .isFalse();

    Integer count =
        JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM document WHERE title = 'Uploaded Over Http'")
            .query(Integer.class)
            .single();
    assertThat(count).as("the upload must have been accepted").isEqualTo(1);
  }

  @Test
  @DisplayName("a multipart upload without the CSRF token is rejected")
  void uploadWithoutTokenIsRejected() throws IOException {
    signIn("admin@a.test");
    Path pdf = writePdf(tempDir, "no-token.pdf", List.of("This upload carries no token."));

    // Same request as the successful case, minus the token: this is the check
    // that the previous test is not passing for want of CSRF protection.
    ResponseEntity<String> response = uploadMultipartWithoutToken("/admin/documents", pdf);

    assertThat(response.getStatusCode().value())
        .as("a tokenless multipart POST must be refused")
        .isEqualTo(403);

    assertThat(
            JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM document")
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("another tenant cannot replace a document over HTTP, even with a valid token")
  void replaceAcrossTenantsIsRefusedOverHttp() throws IOException {
    // Tenant A uploads, then signs out; tenant B signs in and aims A's url at it.
    signIn("admin@a.test");
    String tokenA =
        csrfTokenFrom(http.getForEntity("/admin/documents", String.class).getBody());
    Path original = writePdf(tempDir, "a-doc.pdf", List.of("Northwind confidential content."));
    uploadMultipart("/admin/documents", tokenA, "Northwind Doc", original);

    UUID documentA =
        JdbcClient.create(dataSource)
            .sql("SELECT id FROM document WHERE title = 'Northwind Doc'")
            .query(UUID.class)
            .single();

    signOut();
    signIn("admin@b.test");
    String tokenB =
        csrfTokenFrom(http.getForEntity("/admin/documents", String.class).getBody());

    Path replacement = writePdf(tempDir, "b-doc.pdf", List.of("Contoso replacement content."));
    ResponseEntity<String> response =
        uploadMultipart("/admin/documents/" + documentA + "/versions", tokenB, null, replacement);

    assertThat(response.getStatusCode().value())
        .as("a cross-tenant replacement must look like a missing document")
        .isEqualTo(404);

    // And tenant A's document is untouched: still one version, still its content.
    assertThat(
            JdbcClient.create(dataSource)
                .sql("SELECT count(*) FROM document_version WHERE document_id = :id")
                .param("id", documentA)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  private ResponseEntity<String> uploadMultipartWithoutToken(String path, Path file)
      throws IOException {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("title", "No Token");
    body.add("file", pdfResource(file));
    return http.exchange(path, HttpMethod.POST, multipartEntity(body), String.class);
  }

  // --- browser-ish helpers --------------------------------------------------

  /** Logs in using the same form and CSRF token the login page renders. */
  private void signIn(String email) {
    String loginPage = http.getForEntity("/login", String.class).getBody();
    String token = csrfTokenFrom(loginPage);

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("username", email);
    form.add("password", PASSWORD);
    form.add("_csrf", token);

    ResponseEntity<String> response =
        http.postForEntity("/login", formEntity(form), String.class);

    assertThat(response.getStatusCode().isError())
        .as("login failed with %s", response.getStatusCode())
        .isFalse();

    // The session must reach an *authenticated* page. Asserting the status alone
    // would prove nothing: redirects are followed, so an unauthenticated request
    // also ends at the login page with a 200. Assert on content that only the
    // authenticated admin page renders.
    String adminPage = http.getForEntity("/admin/documents", String.class).getBody();
    assertThat(adminPage)
        .as("the session cookie was not accepted; the admin page was not rendered")
        .isNotNull()
        .contains("Indexed documents");
  }

  private void signOut() {
    String token = csrfTokenFrom(http.getForEntity("/admin/documents", String.class).getBody());
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("_csrf", token);
    http.postForEntity("/logout", formEntity(form), String.class);
  }

  private ResponseEntity<String> uploadMultipart(
      String path, String token, String title, Path file) throws IOException {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    if (title != null) {
      body.add("title", title);
    }
    body.add("file", pdfResource(file));
    body.add("_csrf", token);
    return http.exchange(path, HttpMethod.POST, multipartEntity(body), String.class);
  }

  private static HttpEntity<MultiValueMap<String, String>> formEntity(
      MultiValueMap<String, String> form) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    return new HttpEntity<>(form, headers);
  }

  private static HttpEntity<MultiValueMap<String, Object>> multipartEntity(
      MultiValueMap<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return new HttpEntity<>(body, headers);
  }

  private static ByteArrayResource pdfResource(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    return new ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return "upload.pdf";
      }
    };
  }

  private static String csrfTokenFrom(String html) {
    assertThat(html).as("expected a page containing a CSRF field").isNotNull();
    Matcher matcher = CSRF_FIELD.matcher(html);
    assertThat(matcher.find())
        .as("no CSRF hidden field found in the page; the form would be unusable in a browser")
        .isTrue();
    return matcher.group(1);
  }
}
