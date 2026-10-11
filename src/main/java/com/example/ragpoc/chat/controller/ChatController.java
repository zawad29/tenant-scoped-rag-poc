package com.example.ragpoc.chat.controller;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.retrieval.service.RetrievalService;
import com.example.ragpoc.user.entity.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * A retrieval console, not a chat: chat generation is not implemented, so the
 * reply area is a disabled placeholder and the page shows what retrieval found
 * instead.
 *
 * <p>{@link RetrievalService} is injected as an {@link ObjectProvider} so the
 * ONNX pipeline is built on the first question rather than at context startup;
 * a missing model is then reported in the panel instead of failing the boot.
 */
@Controller
@Slf4j
public class ChatController {

  private final ObjectProvider<RetrievalService> retrieval;
  private final CidService cids;

  public ChatController(ObjectProvider<RetrievalService> retrieval, CidService cids) {
    this.retrieval = retrieval;
    this.cids = cids;
  }

  @GetMapping("/chat")
  public String chat(CidContext cid, CsrfToken csrfToken, Model model) {
    populate(cid, csrfToken, model);
    model.addAttribute("question", null);
    model.addAttribute("outcome", null);
    model.addAttribute("error", null);
    return "chat/chat";
  }

  @PostMapping("/chat")
  public String ask(
      CidContext cid,
      @RequestParam("question") String question,
      CsrfToken csrfToken,
      Model model) {

    populate(cid, csrfToken, model);
    model.addAttribute("question", question);
    try {
      RetrievalOutcome outcome = retrieval.getObject().retrieve(cid, question);
      model.addAttribute("outcome", outcome);
      model.addAttribute("error", null);
    } catch (RuntimeException e) {
      log.warn("Retrieval console request failed: {}", e.toString());
      model.addAttribute("outcome", null);
      model.addAttribute("error", friendlyMessage(e));
    }
    return "chat/chat";
  }

  private void populate(CidContext cid, CsrfToken csrfToken, Model model) {
    model.addAttribute("cidName", cids.require(cid.cidId()).getName());
    model.addAttribute("isCidAdmin", cid.role() == Role.CID_ADMIN);
    model.addAttribute("csrfToken", csrfToken.getToken());
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
  }

  private static String friendlyMessage(RuntimeException e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage() == null ? "" : root.getMessage();
    if (message.contains("Tokenizer not found")
        || message.contains("ONNX")
        || message.contains("model")) {
      return "Retrieval models are not available. Run scripts/fetch-models.sh, then restart the application.";
    }
    return "Retrieval failed: " + root.getClass().getSimpleName();
  }
}
