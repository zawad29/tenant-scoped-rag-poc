package com.example.ragpoc.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits text into sentences.
 *
 * <p>Deliberately conservative: it is better to keep two sentences together than
 * to cut a citation or an abbreviation in half. Splitting "see Fig." from "3 for
 * details" would corrupt the text that the model is asked to quote from, and
 * chunk boundaries only need to be roughly sentence-aligned.
 *
 * <p>One heuristic that was tried and removed: not splitting when the following
 * word begins with a lowercase letter. It was meant to repair PDF line-break
 * artefacts, but it also merged genuinely separate sentences any time the next
 * one happened to start with a lowercase symbol or a variable name, which is
 * worse than the artefact it fixed.
 */
public final class SentenceSplitter {

  /**
   * Trailing words that end in a period without ending a sentence. Compared
   * after stripping punctuation and lower-casing.
   */
  private static final Set<String> ABBREVIATIONS =
      Set.of(
          "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "eg", "ie", "fig",
          "figs", "no", "nos", "inc", "ltd", "co", "corp", "dept", "est", "approx", "min",
          "max", "p", "pp", "vol", "sec", "art", "para", "ch", "cf", "al", "ca", "ed", "eds",
          "ref", "refs", "eq", "cl", "subpara", "sch");

  /** A word ending in sentence punctuation, optionally followed by closing marks. */
  private static final Pattern ENDS_SENTENCE = Pattern.compile(".*[.!?][\"'”’)\\]]*$");

  /** A single letter followed by a period, e.g. an initial in "J. Smith". */
  private static final Pattern INITIAL = Pattern.compile("^[A-Za-z]$");

  /** A dotted acronym such as "U.S." or "Ph.D.". */
  private static final Pattern DOTTED_ACRONYM = Pattern.compile("^(?:[A-Za-z]\\.){2,}$");

  private SentenceSplitter() {}

  /** Splits prose into sentences, preserving the original wording exactly. */
  public static List<String> split(String text) {
    List<String> sentences = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return sentences;
    }

    String[] words = text.trim().split("\\s+");
    StringBuilder current = new StringBuilder();

    for (int i = 0; i < words.length; i++) {
      String word = words[i];
      if (current.length() > 0) {
        current.append(' ');
      }
      current.append(word);

      if (!ENDS_SENTENCE.matcher(word).matches()) {
        continue;
      }
      if (isAbbreviation(word) || isInitial(word) || isDottedAcronym(word)) {
        continue;
      }

      sentences.add(current.toString());
      current.setLength(0);
    }

    if (current.length() > 0) {
      sentences.add(current.toString());
    }
    return sentences;
  }

  private static boolean isAbbreviation(String word) {
    String bare = word.replaceAll("[^A-Za-z.]", "").replace(".", "").toLowerCase();
    return !bare.isEmpty() && ABBREVIATIONS.contains(bare);
  }

  private static boolean isInitial(String word) {
    // "J." is an initial; "e.g." is caught by the abbreviation set.
    String bare = word.replaceAll("[^A-Za-z]", "");
    return bare.length() == 1 && INITIAL.matcher(bare).matches();
  }

  private static boolean isDottedAcronym(String word) {
    return DOTTED_ACRONYM.matcher(word).matches();
  }
}
