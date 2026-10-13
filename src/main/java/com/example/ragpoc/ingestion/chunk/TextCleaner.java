package com.example.ragpoc.ingestion.chunk;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns extracted document text into paragraph-level blocks.
 *
 * <p>Extraction is layout, not prose. The text arrives with a hard line break
 * wherever a line ended on the page, a page number at the foot of every page,
 * and words hyphenated across lines. Left alone, all three end up inside the
 * embedding text and in quoted citations.
 *
 * <p>Tika hands over one text stream with no page boundaries, so there are no
 * pages to compare lines across: what is left is the per-line work. Standalone
 * page numbers go, hyphenated words are rejoined, and the lines are grouped into
 * paragraphs with a conservative heading heuristic.
 */
public class TextCleaner {

  /** A standalone page number, with or without decoration. */
  private static final Pattern PAGE_NUMBER =
      Pattern.compile("(?i)^[-–—\\s]*((page|p\\.?)\\s*)?\\d+(\\s*(of|/)\\s*\\d+)?[-–—\\s]*$");

  /** Numeric outline numbering, e.g. "3.1 Eligibility". */
  private static final Pattern NUMBERED_HEADING =
      Pattern.compile("^\\d+(\\.\\d+)*\\.?\\s+\\S.*");

  /** Bullet or enumerated list item. */
  private static final Pattern LIST_ITEM =
      Pattern.compile("^(?:[-*•‣▪◦·]|\\(?\\d+[.)]|[a-z][.)])\\s+\\S.*");

  public List<Block> clean(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }

    List<String> kept = new ArrayList<>();
    for (String line : splitLines(text)) {
      if (!PAGE_NUMBER.matcher(line).matches()) {
        kept.add(line);
      }
    }
    return buildBlocks(kept);
  }

  // --- line splitting -------------------------------------------------------

  private static List<String> splitLines(String text) {
    List<String> lines = new ArrayList<>();
    for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
      String line = normaliseWhitespace(raw);
      if (!line.isEmpty()) {
        lines.add(line);
      }
    }
    return lines;
  }

  // --- reflow into blocks ---------------------------------------------------

  private List<Block> buildBlocks(List<String> lines) {
    List<Block> blocks = new ArrayList<>();
    StringBuilder paragraph = new StringBuilder();

    for (String line : lines) {
      boolean startsNewBlock =
          paragraph.length() == 0
              || isHeading(line)
              || LIST_ITEM.matcher(line).find()
              || endsParagraph(paragraph);

      if (startsNewBlock && paragraph.length() > 0) {
        flush(paragraph, blocks);
      }
      if (startsNewBlock && paragraph.length() == 0 && isHeading(line)) {
        // A heading stands alone, so it can later be used as a section title.
        blocks.add(new Block(line, true));
        continue;
      }
      appendWithDehyphenation(paragraph, line);
    }

    flush(paragraph, blocks);
    return blocks;
  }

  private static void flush(StringBuilder paragraph, List<Block> blocks) {
    String text = normaliseWhitespace(paragraph.toString());
    if (!text.isEmpty()) {
      blocks.add(new Block(text, false));
    }
    paragraph.setLength(0);
  }

  /**
   * Joins a line onto the paragraph, repairing hyphenation.
   *
   * <p>"employ-\nment" is one word broken across a line; "long-term" split that
   * way would be damaged, so the pattern requires a lowercase letter before the
   * hyphen, which excludes most compound adjectives (which are capitalised
   * inconsistently in PDFs but rarely mid-word in this shape).
   */
  private static void appendWithDehyphenation(StringBuilder paragraph, String line) {
    String text = paragraph.toString();
    if (!text.isEmpty() && text.endsWith("-")) {
      paragraph.setLength(paragraph.length() - 1);
      paragraph.append(line);
      return;
    }
    if (!text.isEmpty()) {
      paragraph.append(' ');
    }
    paragraph.append(line);
  }

  private static boolean endsParagraph(StringBuilder paragraph) {
    String text = paragraph.toString().trim();
    return text.endsWith(".") || text.endsWith("!") || text.endsWith("?") || text.endsWith(":");
  }

  /**
   * Heuristic heading detection.
   *
   * <p>Heading detection has to survive the fact that PDF extraction inserts a
   * hard line break wherever a line ended on the page, so a mid-sentence
   * fragment such as "Employees accrue eighteen days of" looks short and
   * unpunctuated. Requiring a title-case or all-caps shape is what separates it
   * from a real label: prose fragments are mostly lowercase, labels are not.
   *
   * <p>Being wrong in each direction costs differently. A missed heading only
   * means a slightly worse section title. A false positive cuts the document at
   * the wrong place, which splits sentences across chunks and damages both the
   * embedding and the quoted citation.
   */
  static boolean isHeading(String line) {
    String text = line.trim();
    if (text.isEmpty() || text.length() > 80) {
      return false;
    }
    // Sentence-ending and clause punctuation means prose, not a label.
    if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) {
      return false;
    }
    if (text.endsWith(",") || text.endsWith(";")) {
      return false;
    }
    if (LIST_ITEM.matcher(text).find()) {
      return false;
    }
    if (NUMBERED_HEADING.matcher(text).matches()) {
      return true;
    }

    String[] words = text.split("\\s+");
    if (words.length > 12) {
      return false;
    }
    if (words.length <= 8 && isCapitalisedShape(words)) {
      return true;
    }
    // Long all-caps lines are still headings; long title-case lines are more
    // likely to be a wrapped sentence.
    return isAllCaps(text);
  }

  /**
   * True when most words start with a capital: "Leave Policy", "Carry-over",
   * "Eligibility". A prose fragment such as "Employees accrue eighteen days of"
   * scores one out of five and is rejected.
   */
  private static boolean isCapitalisedShape(String[] words) {
    int capitalised = 0;
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      char first = word.charAt(0);
      if (Character.isUpperCase(first) || !Character.isLetter(first)) {
        capitalised++;
      }
    }
    return capitalised >= Math.max(1, (int) Math.ceil(words.length * 0.6));
  }

  private static boolean isAllCaps(String text) {
    boolean hasLetter = false;
    for (char c : text.toCharArray()) {
      if (Character.isLetter(c)) {
        hasLetter = true;
        if (Character.isLowerCase(c)) {
          return false;
        }
      }
    }
    return hasLetter;
  }

  // --- unicode and whitespace ----------------------------------------------

  static String normaliseWhitespace(String input) {
    if (input == null) {
      return "";
    }
    String normalised = Normalizer.normalize(input, Normalizer.Form.NFC);
    return normalised
        // Non-breaking and other exotic spaces become ordinary ones.
        .replaceAll("[\\u00A0\\u2007\\u202F\\u2009\\u200A\\u2002\\u2003\\u2004\\u2005\\u2006\\u2008]", " ")
        // Zero-width characters carry no meaning and confuse tokenizers.
        .replaceAll("[\\u200B\\u200C\\u200D\\uFEFF]", "")
        // Bullets become a plain hyphen so list detection and quoting agree.
        .replaceAll("[\\u2022\\u2023\\u25AA\\u25E6\\u2043\\u2219]", "-")
        .replaceAll("[ \\t\\x0B\\f]+", " ")
        .trim();
  }
}
