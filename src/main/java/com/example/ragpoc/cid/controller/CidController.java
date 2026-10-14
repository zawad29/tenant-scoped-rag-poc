package com.example.ragpoc.cid.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.cid.entity.Cid;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.web.ShellModel;
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
  private final ShellModel shell;

  public CidController(CidService cids, ShellModel shell) {
    this.cids = cids;
    this.shell = shell;
  }

  @GetMapping("/")
  public String home(
      CidContext cid,
      @AuthenticationPrincipal AppUserPrincipal principal,
      CsrfToken csrfToken,
      Model model) {

    shell.add(model, cid, principal, csrfToken, "home");
    model.addAttribute("cid", cids.require(cid.cidId()));
    return "home";
  }

  @GetMapping("/cid/profile")
  public String profile(
      CidContext cid,
      @AuthenticationPrincipal AppUserPrincipal principal,
      CsrfToken csrfToken,
      @ModelAttribute("message") String message,
      Model model) {

    shell.add(model, cid, principal, csrfToken, "profile");
    model.addAttribute("cid", cids.require(cid.cidId()));
    model.addAttribute("message", message);
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
}
