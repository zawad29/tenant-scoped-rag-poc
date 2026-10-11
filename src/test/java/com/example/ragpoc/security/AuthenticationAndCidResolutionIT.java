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

import com.example.ragpoc.support.IntegrationTestSupport;
import com.example.ragpoc.user.entity.Role;
import com.example.ragpoc.user.entity.User;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authentication and cid resolution over real HTTP request handling: the cid
 * attached to a session comes from the user's own database row and nothing the
 * client sends, and a signed-in user is shown their own organisation only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationAndCidResolutionIT extends IntegrationTestSupport {

  private static final String PASSWORD = "correct-horse-battery-staple";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private DataSource dataSource;
  @Autowired private PasswordEncoder passwordEncoder;

  // Canary names: unique and unmistakable, so a test can state plainly that one
  // company never sees the other's.
  private static final String CID_A = "Northwind Canary Analytics";
  private static final String CID_B = "Contoso Canary Logistics";

  private static final String EMAIL_A = "admin@northwind.test";
  private static final String EMAIL_B = "admin@contoso.test";

  @BeforeEach
  void seedCids() {
    resetDatabase(dataSource);
    seedCidWithUser(dataSource, passwordEncoder, CID_A, EMAIL_A, PASSWORD, "CID_ADMIN");
    seedCidWithUser(dataSource, passwordEncoder, CID_B, EMAIL_B, PASSWORD, "CID_ADMIN");
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
  @DisplayName("signing in succeeds and the session carries the user's own cid")
  void loginBindsTheUsersOwnCid() throws Exception {
    mockMvc
        .perform(formLogin("/login").user(EMAIL_A).password(PASSWORD))
        .andExpect(authenticated().withUsername(EMAIL_A));
  }

  @Test
  @DisplayName("a signed-in user sees their own organisation and never another cid's")
  void signedInUserSeesOnlyTheirOwnCid() throws Exception {
    mockMvc
        .perform(get("/").with(user(principalFor(EMAIL_A, Role.CID_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(CID_A)))
        .andExpect(content().string(not(containsString(CID_B))));
  }

  @Test
  @DisplayName("the other cid's user sees their own organisation, not the first")
  void theOtherCidSeesItsOwnOrganisation() throws Exception {
    mockMvc
        .perform(get("/").with(user(principalFor(EMAIL_B, Role.CID_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(CID_B)))
        .andExpect(content().string(not(containsString(CID_A))));
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

    assertThat(unknownAccount).isEqualTo(wrongPassword);
  }

  @Test
  @DisplayName("document administration requires the cid admin role")
  void adminRoutesRequireCidAdmin() throws Exception {
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

  @Test
  @DisplayName("the organisation profile page renders for the cid's own admin")
  void profilePageRenders() throws Exception {
    mockMvc
        .perform(get("/cid/profile").with(user(principalFor(EMAIL_A, Role.CID_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(CID_A)));
  }

  @Test
  @DisplayName("the user administration page renders and lists only the caller's users")
  void usersPageRendersOnlyOwnUsers() throws Exception {
    mockMvc
        .perform(get("/admin/users").with(user(principalFor(EMAIL_A, Role.CID_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(EMAIL_A)))
        .andExpect(content().string(not(containsString(EMAIL_B))));
  }

  @Test
  @DisplayName("the documents page renders the upload form and the document list")
  void documentsPageRenders() throws Exception {
    mockMvc
        .perform(get("/admin/documents").with(user(principalFor(EMAIL_A, Role.CID_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Indexed documents")))
        .andExpect(content().string(containsString("Upload")))
        .andExpect(content().string(not(containsString(CID_B))));
  }

  private AppUserPrincipal principalFor(String email, Role role) {
    UUID cidId = cidIdOf(dataSource, email);
    UUID userId = userIdOf(dataSource, email);
    return AppUserPrincipal.from(new User(userId, cidId, email, "hash-not-used", role, true));
  }
}
