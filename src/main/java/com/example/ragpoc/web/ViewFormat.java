package com.example.ragpoc.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Small formatting helpers for templates, so views hold no logic. */
public final class ViewFormat {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("d MMM HH:mm:ss").withZone(ZoneId.systemDefault());

  private ViewFormat() {}

  public static String time(Instant instant) {
    return instant == null ? "-" : TIME.format(instant);
  }

  /** CSS class for a status pill. */
  public static String statusClass(String status) {
    return "status status-" + status.toLowerCase(java.util.Locale.ROOT);
  }

  /** Formats a chunk count, or a dash when the version has never completed. */
  public static String count(Integer value) {
    return value == null ? "-" : String.valueOf(value);
  }

  /** Version number, or a dash before the first successful ingestion. */
  public static String version(int currentVersion) {
    return currentVersion == 0 ? "-" : String.valueOf(currentVersion);
  }

  /** Formats a raw score to three decimals. */
  public static String score(double value) {
    return String.format(java.util.Locale.ROOT, "%.3f", value);
  }

  /** Formats a nullable raw score, or a dash when there is none. */
  public static String score(Double value) {
    return value == null ? "-" : score(value.doubleValue());
  }

  /** Formats a relevance in (0, 1) for display. */
  public static String relevance(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  /** Formats a millisecond duration. */
  public static String ms(long value) {
    return value + " ms";
  }

  /** Formats a millisecond duration that may not have been measured. */
  public static String msOrDash(long value) {
    return value > 0 ? value + " ms" : "—";
  }

  /** Formats a score, or a dash when there is none (for example -Infinity). */
  public static String finiteScore(double value) {
    return Double.isFinite(value) ? score(value) : "—";
  }

  /** Renders an identifier for a URL; JTE writes only Strings. */
  public static String id(java.util.UUID value) {
    return value == null ? "" : value.toString();
  }
}
