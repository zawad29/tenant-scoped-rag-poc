package com.example.ragpoc.chat.controller;

import com.example.ragpoc.chat.ChatEvent;
import com.example.ragpoc.chat.ChatTimings;
import com.example.ragpoc.chat.service.ChatService;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.user.entity.Role;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The chat screen.
 *
 * <p>Three endpoints, one feature:
 *
 * <ul>
 *   <li>{@code GET /chat} renders the shell;
 *   <li>{@code POST /chat} is the no-JavaScript fallback: it retrieves, waits
 *       for the whole answer, and renders a page with both;
 *   <li>{@code POST /chat/stream} answers the fetch-driven UI with server-sent
 *       events, so text appears as the model produces it.
 * </ul>
 *
 * <p>The retrieval panel is rendered by JTE in both paths and travels as an HTML
 * fragment inside a JSON-encoded event, which keeps the panel markup in one
 * template and the browser script to appending rather than building DOM.
 */
@Controller
@Slf4j
public class ChatController {

  private final ChatService chat;
  private final CidService cids;
  private final TemplateEngine templates;
  private final ObjectMapper json;

  public ChatController(
      ChatService chat, CidService cids, TemplateEngine templates, ObjectMapper json) {
    this.chat = chat;
    this.cids = cids;
    this.templates = templates;
    this.json = json;
  }

  @GetMapping("/chat")
  public String chat(CidContext cid, CsrfToken csrfToken, Model model) {
    populate(cid, csrfToken, model);
    model.addAttribute("question", null);
    model.addAttribute("outcome", null);
    model.addAttribute("retrievalMillis", 0L);
    model.addAttribute("timings", null);
    model.addAttribute("answer", null);
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
      ChatService.ChatReply reply = chat.ask(cid, question);
      model.addAttribute("outcome", reply.outcome());
      model.addAttribute("retrievalMillis", reply.timings().retrievalMillis());
      model.addAttribute("timings", reply.timings());
      model.addAttribute("answer", reply.answer());
      model.addAttribute("error", null);
    } catch (RuntimeException e) {
      log.warn("Chat request failed: {}", e.toString());
      model.addAttribute("outcome", null);
      model.addAttribute("retrievalMillis", 0L);
      model.addAttribute("timings", null);
      model.addAttribute("answer", null);
      model.addAttribute("error", ChatService.friendlyMessage(e));
    }
    return "chat/chat";
  }

  /**
   * The streaming path.
   *
   * <p>Returns a {@code Flux} rather than an {@code SseEmitter} so the pipeline
   * stays declarative: the controller only renames domain events into the SSE
   * protocol, and Spring MVC writes them as they arrive.
   */
  @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ResponseBody
  public Flux<ServerSentEvent<String>> stream(
      CidContext cid, @RequestParam("question") String question) {
    log.info("Chat stream requested cid={} user={}", cid.cidId(), cid.userId());
    return chat.stream(cid, question).map(this::toEvent);
  }

  private ServerSentEvent<String> toEvent(ChatEvent event) {
    return switch (event) {
      case ChatEvent.Retrieved retrieved ->
          ssEvent("panel", Map.of("html", renderPanel(retrieved.outcome(), retrieved.retrievalMillis(), null)));
      case ChatEvent.Completed completed ->
          ssEvent(
              "panel",
              Map.of(
                  "html",
                  renderPanel(
                      completed.outcome(),
                      completed.timings().retrievalMillis(),
                      completed.timings())));
      case ChatEvent.Delta delta -> ssEvent("delta", Map.of("text", delta.text()));
      case ChatEvent.Failed failed -> ssEvent("error", Map.of("message", failed.message()));
    };
  }

  private String renderPanel(
      RetrievalOutcome outcome, long retrievalMillis, ChatTimings timings) {
    Map<String, Object> params = new HashMap<>();
    params.put("outcome", outcome);
    params.put("retrievalMillis", retrievalMillis);
    params.put("timings", timings);
    StringOutput output = new StringOutput();
    templates.render("chat/panel.jte", params, output);
    return output.toString();
  }

  /**
   * Every event is JSON-encoded, including the HTML ones: a raw newline would
   * end the SSE data field early, and JSON escapes it.
   */
  private ServerSentEvent<String> ssEvent(String name, Object payload) {
    String data;
    try {
      data = json.writeValueAsString(payload);
    } catch (JacksonException e) {
      log.error("Could not serialise a chat event", e);
      data = "{\"message\":\"The answer could not be rendered.\"}";
    }
    return ServerSentEvent.<String>builder().event(name).data(data).build();
  }

  private void populate(CidContext cid, CsrfToken csrfToken, Model model) {
    model.addAttribute("cidName", cids.require(cid.cidId()).getName());
    model.addAttribute("isCidAdmin", cid.role() == Role.CID_ADMIN);
    model.addAttribute("csrfToken", csrfToken.getToken());
    model.addAttribute("csrfParameterName", csrfToken.getParameterName());
  }
}
