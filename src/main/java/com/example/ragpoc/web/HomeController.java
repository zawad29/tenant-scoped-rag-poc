package com.example.ragpoc.web;

import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.tenant.Tenant;
import com.example.ragpoc.tenant.TenantContext;
import com.example.ragpoc.tenant.TenantRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Landing page.
 *
 * <p>Kept deliberately thin, but not a stub: it is where the principal to tenant
 * conversion is exercised end to end. The page shows the calling tenant's own
 * name, which is how the isolation test confirms that a signed-in user sees
 * their organisation and no other.
 */
@Controller
public class HomeController {

  private final TenantRepository tenants;

  public HomeController(TenantRepository tenants) {
    this.tenants = tenants;
  }

  @GetMapping("/")
  public String home(
      TenantContext tenant,
      @AuthenticationPrincipal AppUserPrincipal principal,
      // The sign-out form is a POST, so it needs the token. Omitting it would
      // work in tests that inject a token and fail in a browser.
      CsrfToken csrfToken,
      Model model) {

    // The tenant id comes from the authenticated principal via the argument
    // resolver; nothing here reads a tenant from the request.
    Tenant own =
        tenants
            .findOwn(tenant.tenantId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Authenticated tenant " + tenant.tenantId() + " no longer exists"));

    model.addAttribute("tenantName", own.name());
    model.addAttribute("email", principal.getUsername());
    model.addAttribute("role", tenant.role().name());
    model.addAttribute("isTenantAdmin", tenant.role() == com.example.ragpoc.tenant.Role.TENANT_ADMIN);
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
    model.addAttribute("csrfToken", csrfToken.getToken());
    return "home";
  }
}
