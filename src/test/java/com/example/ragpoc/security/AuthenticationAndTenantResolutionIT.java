package com.example.ragpoc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ragpoc.support.PostgresTestSupport;
import com.example.ragpoc.tenant.AppUser;
import com.example.ragpoc.tenant.Role;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 moved MVC test slicing to its own module and package:
// org.springframework.boot.test.autoconfigure.web.servlet is gone, and
// @WebMvcTest lives alongside @AutoConfigureMockMvc in webmvc.test.autoconfigure.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authentication and tenant resolution over real HTTP request handling.
 *
 * <p>The tenant-isolation suite proper arrives with the retrieval layer. What is
 * established here is the prior question: the tenant attached to a session comes
 * from the user's own database row and nothing the client sends, and a signed-in
 * user is shown their own organisation and no other.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationAndTenantResolutionIT extends PostgresTestSupport {

  private static final String PASSWORD = "correct-horse-battery-staple";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerDatasourceProperties(registry);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private DataSource dataSource;
  @Autowired private PasswordEncoder passwordEncoder;

  // Canary organisation names: unique and unmistakable, so a test can state
  // plainly that one tenant never sees the other's.
  private static final String TENANT_A = "Northwind Canary Analytics";
  private static final String TENANT_B = "Contoso Canary Logistics";

  private static final String EMAIL_A = "admin@northwind.test";
  private static final String EMAIL_B = "admin@contoso.test";

  @BeforeEach
  void seedTenants() {
    resetDatabase(dataSource);
    seedTenantWithUser(dataSource, passwordEncoder, TENANT_A, EMAIL_A, PASSWORD, "TENANT_ADMIN");
    seedTenantWithUser(dataSource, passwordEncoder, TENANT_B, EMAIL_B, PASSWORD, "TENANT_ADMIN");
  }

  @Test
  @DisplayName("an anonymous request to a protected page is redirected to the login page")
  void anonymousIsRedirectedToLogin() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"))
        .andExpect(unauthenticated());
  }

  @Test
  @DisplayName("the login page renders without authenticating")
  void loginPageIsPublic() throws Exception {
    mockMvc
        .perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Sign in")));
  }

  @Test
  @DisplayName("signing in succeeds and the session carries the user's own tenant")
  void loginBindsTheUsersOwnTenant() throws Exception {
    mockMvc
        .perform(formLogin("/login").user(EMAIL_A).password(PASSWORD))
        .andExpect(authenticated().withUsername(EMAIL_A));
  }

  @Test
  @DisplayName("a signed-in user sees their own organisation and never another tenant's")
  void signedInUserSeesOnlyTheirOwnTenant() throws Exception {
    mockMvc
        .perform(get("/").with(user(principalFor(EMAIL_A, Role.TENANT_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(TENANT_A)))
        .andExpect(content().string(not(containsString(TENANT_B))));
  }

  @Test
  @DisplayName("the other tenant's user sees their own organisation, not the first")
  void theOtherTenantSeesItsOwnOrganisation() throws Exception {
    // The mirror of the previous test: proves the first test is not passing
    // merely because the page is blank or the assertion is one-sided.
    mockMvc
        .perform(get("/").with(user(principalFor(EMAIL_B, Role.TENANT_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(TENANT_B)))
        .andExpect(content().string(not(containsString(TENANT_A))));
  }

  @Test
  @DisplayName("a wrong password is rejected")
  void wrongPasswordIsRejected() throws Exception {
    mockMvc
        .perform(formLogin("/login").user(EMAIL_A).password("wrong-password"))
        .andExpect(unauthenticated())
        .andExpect(redirectedUrl("/login?error"));
  }

  @Test
  @DisplayName("an unknown account is indistinguishable from a wrong password")
  void unknownAccountIsIndistinguishableFromWrongPassword() throws Exception {
    String unknownAccount =
        mockMvc
            .perform(formLogin("/login").user("nobody@nowhere.test").password("whatever"))
            .andReturn()
            .getResponse()
            .getRedirectedUrl();

    String wrongPassword =
        mockMvc
            .perform(formLogin("/login").user(EMAIL_B).password("wrong-password"))
            .andReturn()
            .getResponse()
            .getRedirectedUrl();

    // Identical outcomes, so account existence is not observable — and neither
    // is which organisations are enrolled.
    assertThat(unknownAccount).isEqualTo(wrongPassword);
  }

  @Test
  @DisplayName("document administration requires the tenant admin role")
  void adminRoutesRequireTenantAdmin() throws Exception {
    mockMvc
        .perform(get("/admin/documents").with(user(principalFor(EMAIL_A, Role.USER))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("signing out ends the session")
  void logoutEndsTheSession() throws Exception {
    mockMvc
        .perform(logout("/logout"))
        .andExpect(unauthenticated())
        .andExpect(redirectedUrl("/login?logout"));
  }

  // --- helpers --------------------------------------------------------------

  private AppUserPrincipal principalFor(String email, Role role) {
    UUID tenantId = tenantIdOf(dataSource, email);
    UUID userId =
        org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
            .sql("SELECT id FROM app_user WHERE lower(email) = lower(:email)")
            .param("email", email)
            .query(UUID.class)
            .single();
    return AppUserPrincipal.from(
        new AppUser(userId, tenantId, email, "hash-not-used-by-mockmvc", role, true));
  }
}
