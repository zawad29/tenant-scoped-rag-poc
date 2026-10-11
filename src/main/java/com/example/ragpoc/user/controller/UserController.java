package com.example.ragpoc.user.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.user.entity.Role;
import com.example.ragpoc.user.service.UserService;
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

  public UserController(UserService users) {
    this.users = users;
  }

  @GetMapping("/admin/users")
  public String list(
      CidContext cid,
      CsrfToken csrfToken,
      @ModelAttribute("message") String message,
      @ModelAttribute("error") String error,
      Model model) {
    model.addAttribute("users", users.list(cid.cidId()));
    model.addAttribute("roles", Role.values());
    model.addAttribute("message", message);
    model.addAttribute("error", error);
    model.addAttribute("csrfToken", csrfToken.getToken());
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
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
