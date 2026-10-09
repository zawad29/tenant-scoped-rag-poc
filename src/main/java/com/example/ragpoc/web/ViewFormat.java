package com.example.ragpoc.web;

import com.example.ragpoc.document.DocumentStatus;
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
  public static String statusClass(DocumentStatus status) {
    return "status status-" + status.name().toLowerCase(java.util.Locale.ROOT);
  }

  /** Human-readable label for a job state, or a dash when there is no job. */
  public static String jobLabel(String jobState) {
    if (jobState == null || jobState.isBlank()) {
      return "-";
    }
    return jobState.charAt(0) + jobState.substring(1).toLowerCase(java.util.Locale.ROOT);
  }

  /** Formats a chunk count, or a dash when the version has never completed. */
  public static String count(Integer value) {
    return value == null ? "-" : String.valueOf(value);
  }

  /** Version number, or a dash before the first successful ingestion. */
  public static String version(int currentVersion) {
    return currentVersion == 0 ? "-" : String.valueOf(currentVersion);
  }

  /**
   * Renders an identifier for a URL.
   *
   * <p>JTE writes only Strings, so a {@link java.util.UUID} has to be converted
   * here rather than relied on to be coerced.
   */
  public static String id(java.util.UUID value) {
    return value == null ? "" : value.toString();
  }
}
