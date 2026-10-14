package com.example.ragpoc.web;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.user.entity.Role;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Fills the model attributes the shared layout renders.
 *
 * <p>Every authenticated page needs the same shell values — the cid name, who is
 * signed in, the CSRF token and which navigation item is current — so they are
 * added in one place rather than repeated in each controller. The cid still
 * comes from {@link CidContext}, which comes from the principal; nothing here
 * reads it from the request.
 */
@Component
public class ShellModel {

  private final CidService cids;

  public ShellModel(CidService cids) {
    this.cids = cids;
  }

  public void add(
      Model model,
      CidContext cid,
      AppUserPrincipal principal,
      CsrfToken csrfToken,
      String active) {

    model.addAttribute("cidName", cids.require(cid.cidId()).getName());
    model.addAttribute("email", principal == null ? "" : principal.getUsername());
    model.addAttribute("role", cid.role().name());
    model.addAttribute("isCidAdmin", cid.role() == Role.CID_ADMIN);
    model.addAttribute("csrfToken", csrfToken.getToken());
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
    model.addAttribute("active", active);
  }
}
