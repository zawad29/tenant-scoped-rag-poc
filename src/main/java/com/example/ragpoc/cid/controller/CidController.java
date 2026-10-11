package com.example.ragpoc.cid.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.cid.entity.Cid;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.user.entity.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Landing page and the cid's own profile. */
@Controller
public class CidController {

  private final CidService cids;

  public CidController(CidService cids) {
    this.cids = cids;
  }

  @GetMapping("/")
  public String home(
      CidContext cid,
      @AuthenticationPrincipal AppUserPrincipal principal,
      CsrfToken csrfToken,
      Model model) {

    Cid own = cids.require(cid.cidId());
    model.addAttribute("cidName", own.getName());
    model.addAttribute("email", principal.getUsername());
    model.addAttribute("role", cid.role().name());
    model.addAttribute("isCidAdmin", cid.role() == Role.CID_ADMIN);
    addCsrf(csrfToken, model);
    return "home";
  }

  @GetMapping("/cid/profile")
  public String profile(
      CidContext cid,
      CsrfToken csrfToken,
      @ModelAttribute("message") String message,
      Model model) {
    model.addAttribute("cid", cids.require(cid.cidId()));
    model.addAttribute("message", message);
    addCsrf(csrfToken, model);
    return "cid/profile";
  }

  @PostMapping("/cid/profile")
  public String updateProfile(
      CidContext cid,
      @RequestParam("name") String name,
      @RequestParam(value = "address", required = false) String address,
      @RequestParam(value = "email", required = false) String email,
      RedirectAttributes redirect) {
    cids.updateProfile(cid.cidId(), name, address, email);
    redirect.addFlashAttribute("message", "Profile updated.");
    return "redirect:/cid/profile";
  }

  private static void addCsrf(CsrfToken csrfToken, Model model) {
    model.addAttribute("csrfToken", csrfToken.getToken());
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
  }
}
