package com.example.ragpoc.web;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Renders the sign-in page. */
@Controller
public class LoginController {

  @GetMapping("/login")
  public String login(
      @RequestParam(value = "error", required = false) String error,
      @RequestParam(value = "logout", required = false) String logout,
      CsrfToken csrfToken,
      Model model) {

    // The CSRF token is passed explicitly rather than by exposing request
    // attributes to templates: what a template can read should be visible in
    // the controller.
    model.addAttribute("csrfToken", csrfToken.getToken());
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
    model.addAttribute("loginFailed", error != null);
    model.addAttribute("loggedOut", logout != null);
    return "login";
  }
}
