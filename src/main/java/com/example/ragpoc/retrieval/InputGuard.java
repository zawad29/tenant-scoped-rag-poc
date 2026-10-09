package com.example.ragpoc.retrieval;

import com.example.ragpoc.chat.AssistantMessages;
import com.example.ragpoc.config.RagProperties;
import com.example.ragpoc.tenant.TenantContext;
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

/**
 * Checks a question before any retrieval happens.
 *
 * <p>The injection patterns refuse outright rather than merely being flagged.
 * A question that asks the assistant to ignore its instructions or to reveal
 * another organisation's documents has no legitimate answer, and refusing before
 * retrieval means the attempt costs nothing and cannot reach the model at all.
 * Retrieved document text is treated as untrusted too (see the grounded prompt),
 * so this guard and the prompt cover the two directions an injection can come
 * from.
 */
public class InputGuard {

  /** Phrases that only appear in an attempt to redirect the assistant. */
  private static final List<Pattern> INJECTION_PATTERNS =
      List.of(
          Pattern.compile("ignore\\s+(all\\s+)?(the\\s+)?(previous|prior|above)\\s+instructions?", Pattern.CASE_INSENSITIVE),
          Pattern.compile("disregard\\s+(all\\s+)?(the\\s+)?(previous|prior|above)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("(reveal|show|print|repeat)\\s+(me\\s+)?(your\\s+)?(the\\s+)?system\\s+prompt", Pattern.CASE_INSENSITIVE),
          Pattern.compile("(other|another|different)\\s+(organisations?|organizations?|tenants?|companies|clients?)", Pattern.CASE_INSENSITIVE),
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

  /**
   * Result of inspecting a question.
   *
   * @param allowed false when the question must not be retrieved against
   */
  public record GuardResult(
      boolean allowed, String question, String refusalMessage, boolean injectionSuspected) {

    static GuardResult allowed(String question) {
      return new GuardResult(true, question, null, false);
    }

    static GuardResult rejected(String question, String message, boolean injection) {
      return new GuardResult(false, question, message, injection);
    }
  }

  public GuardResult inspect(TenantContext tenant, String rawQuestion) {
    if (rawQuestion == null || rawQuestion.isBlank()) {
      return GuardResult.rejected("", AssistantMessages.REJECTED_INPUT, false);
    }

    // Control characters are stripped rather than rejected: they arrive from
    // pasted content and carry no meaning, whereas a zero-width character
    // between letters can be used to break up a keyword a filter is looking for.
    String question = stripControlCharacters(rawQuestion);

    if (question.length() > maxQuestionChars) {
      return GuardResult.rejected(question, AssistantMessages.REJECTED_INPUT, false);
    }

    for (Pattern pattern : INJECTION_PATTERNS) {
      if (pattern.matcher(question).find()) {
        return GuardResult.rejected(question, AssistantMessages.REJECTED_INPUT, true);
      }
    }

    if (!withinRateLimit(tenant)) {
      return GuardResult.rejected(question, AssistantMessages.REJECTED_INPUT, false);
    }

    return GuardResult.allowed(question);
  }

  private static String stripControlCharacters(String input) {
    StringBuilder cleaned = new StringBuilder(input.length());
    for (char c : input.toCharArray()) {
      // Zero-width joiners, bidi overrides and similar are Unicode FORMAT
      // characters, not ISO controls, so they need their own check. They carry
      // no meaning here and can be used to split a keyword a filter looks for.
      if (Character.getType(c) == Character.FORMAT) {
        continue;
      }
      // C0/C1 controls, keeping the whitespace that carries structure.
      if (Character.isISOControl(c) && c != '\n' && c != '\t') {
        continue;
      }
      cleaned.append(c);
    }
    return cleaned.toString().trim();
  }

  /**
   * A simple per-user sliding window.
   *
   * <p>In-memory and therefore per instance, which is honest for a single-node
   * PoC: a shared store would be needed the moment there is more than one
   * application node, and pretending otherwise would be worse than being
   * explicit.
   */
  private boolean withinRateLimit(TenantContext tenant) {
    if (tenant.userId() == null || requestsPerMinute <= 0) {
      return true;
    }
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
    Deque<Instant> timestamps = recentRequests.computeIfAbsent(tenant.userId(), key -> new ArrayDeque<>());

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
