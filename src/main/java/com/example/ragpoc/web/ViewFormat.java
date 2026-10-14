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

  /** CSS class for a status pill, in Tailwind utility form. */
  public static String statusPill(String status) {
    String tone =
        switch (status == null ? "" : status.toUpperCase(java.util.Locale.ROOT)) {
          case "ACTIVE" -> "bg-emerald-100 text-emerald-800";
          case "FAILED" -> "bg-rose-100 text-rose-800";
          case "PROCESSING", "PENDING" -> "bg-amber-100 text-amber-800";
          default -> "bg-slate-100 text-slate-700";
        };
    return "inline-flex rounded-full px-2 py-0.5 text-xs font-medium " + tone;
  }

  /**
   * Sidebar navigation item classes: the active item is highlighted, the rest
   * are muted. Kept here so the layout holds no conditionals of its own.
   */
  public static String navClass(String active, String key) {
    String base = "flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition";
    return key.equals(active)
        ? base + " bg-slate-700 text-white"
        : base + " text-slate-300 hover:bg-slate-800 hover:text-white";
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
