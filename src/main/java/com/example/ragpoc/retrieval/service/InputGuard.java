package com.example.ragpoc.retrieval.service;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.config.RagProperties;
import com.example.ragpoc.retrieval.AssistantMessages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Checks a question before any retrieval happens.
 *
 * <p>Injection patterns refuse outright rather than being flagged: a question
 * that asks the assistant to ignore its instructions has no legitimate answer,
 * and refusing before retrieval means the attempt costs nothing.
 */
@Slf4j
public class InputGuard {

  private static final List<Pattern> INJECTION_PATTERNS =
      List.of(
          Pattern.compile("ignore\\s+(all\\s+)?(the\\s+)?(previous|prior|above)\\s+instructions?", Pattern.CASE_INSENSITIVE),
          Pattern.compile("disregard\\s+(all\\s+)?(the\\s+)?(previous|prior|above)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("(reveal|show|print|repeat)\\s+(me\\s+)?(your\\s+)?(the\\s+)?system\\s+prompt", Pattern.CASE_INSENSITIVE),
          Pattern.compile("(other|another|different)\\s+(organisations?|organizations?|companies|clients?|cids?)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("(all|every)\\s+(documents?|files?)\\s+(from|across)\\s+(all|every|other)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("you\\s+are\\s+now\\s+(a|an|in)\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("jailbreak|developer\\s+mode|DAN\\s+mode", Pattern.CASE_INSENSITIVE));

  private final int maxQuestionChars;
  private final int requestsPerMinute;
  private final Map<UUID, Deque<Instant>> recentRequests = new ConcurrentHashMap<>();

  public InputGuard(RagProperties properties) {
    this.maxQuestionChars = properties.guard().maxQuestionChars();
    this.requestsPerMinute = properties.guard().requestsPerMinutePerUser();
  }

  public record GuardResult(
      boolean allowed, String question, String refusalMessage, boolean injectionSuspected) {

    static GuardResult allowed(String question) {
      return new GuardResult(true, question, null, false);
    }

    static GuardResult rejected(String question, String message, boolean injection) {
      return new GuardResult(false, question, message, injection);
    }
  }

  public GuardResult inspect(CidContext cid, String rawQuestion) {
    if (rawQuestion == null || rawQuestion.isBlank()) {
      log.debug("retrieve step=guard cid={} allowed=false reason=blank", cid.cidId());
      return GuardResult.rejected("", AssistantMessages.REJECTED_INPUT, false);
    }

    String question = stripControlCharacters(rawQuestion);

    if (question.length() > maxQuestionChars) {
      log.warn(
          "retrieve step=guard cid={} allowed=false reason=too-long chars={} max={}",
          cid.cidId(), question.length(), maxQuestionChars);
      return GuardResult.rejected(question, AssistantMessages.REJECTED_INPUT, false);
    }

    for (Pattern pattern : INJECTION_PATTERNS) {
      if (pattern.matcher(question).find()) {
        log.warn(
            "retrieve step=guard cid={} allowed=false reason=injection pattern=\"{}\"",
            cid.cidId(), pattern.pattern());
        return GuardResult.rejected(question, AssistantMessages.REJECTED_INPUT, true);
      }
    }

    if (!withinRateLimit(cid)) {
      log.warn(
          "retrieve step=guard cid={} user={} allowed=false reason=rate-limit limit={}/min",
          cid.cidId(), cid.userId(), requestsPerMinute);
      return GuardResult.rejected(question, AssistantMessages.REJECTED_INPUT, false);
    }

    log.info("retrieve step=guard cid={} allowed=true chars={}", cid.cidId(), question.length());
    return GuardResult.allowed(question);
  }

  private static String stripControlCharacters(String input) {
    StringBuilder cleaned = new StringBuilder(input.length());
    for (char c : input.toCharArray()) {
      if (Character.getType(c) == Character.FORMAT) {
        continue;
      }
      if (Character.isISOControl(c) && c != '\n' && c != '\t') {
        continue;
      }
      cleaned.append(c);
    }
    return cleaned.toString().trim();
  }

  /** A simple per-user sliding window, in-memory and therefore per instance. */
  private boolean withinRateLimit(CidContext cid) {
    if (cid.userId() == null || requestsPerMinute <= 0) {
      return true;
    }
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
    Deque<Instant> timestamps = recentRequests.computeIfAbsent(cid.userId(), key -> new ArrayDeque<>());

    synchronized (timestamps) {
      while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
        timestamps.pollFirst();
      }
      if (timestamps.size() >= requestsPerMinute) {
        return false;
      }
      timestamps.addLast(Instant.now());
      return true;
    }
  }

  /** Exposed for diagnostics: the pattern that would match, if any. */
  public static String matchedPattern(String question) {
    String normalised = question == null ? "" : question.toLowerCase(Locale.ROOT);
    for (Pattern pattern : INJECTION_PATTERNS) {
      if (pattern.matcher(normalised).find()) {
        return pattern.pattern();
      }
    }
    return null;
  }
}
