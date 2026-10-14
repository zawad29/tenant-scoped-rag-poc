package com.example.ragpoc.user.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.security.AppUserPrincipal;
import com.example.ragpoc.user.entity.Role;
import com.example.ragpoc.user.service.UserService;
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

/** User administration for cid admins. */
@Controller
public class UserController {

  private final UserService users;
  private final ShellModel shell;

  public UserController(UserService users, ShellModel shell) {
    this.users = users;
    this.shell = shell;
  }

  @GetMapping("/admin/users")
  public String list(
      CidContext cid,
      @AuthenticationPrincipal AppUserPrincipal principal,
      CsrfToken csrfToken,
      @ModelAttribute("message") String message,
      @ModelAttribute("error") String error,
      Model model) {
    shell.add(model, cid, principal, csrfToken, "users");
    model.addAttribute("users", users.list(cid.cidId()));
    model.addAttribute("roles", Role.values());
    model.addAttribute("message", message);
    model.addAttribute("error", error);
    return "admin/users";
  }

  @PostMapping("/admin/users")
  public String create(
      CidContext cid,
      @RequestParam("email") String email,
      @RequestParam("password") String password,
      @RequestParam("role") Role role,
      RedirectAttributes redirect) {
    try {
      users.create(cid.cidId(), email, password, role);
      redirect.addFlashAttribute("message", "User created.");
    } catch (RuntimeException e) {
      redirect.addFlashAttribute("error", "Could not create user: " + e.getMessage());
    }
    return "redirect:/admin/users";
  }
}
