package com.example.ragpoc.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SentenceSplitterTest {

  @Test
  @DisplayName("splits prose at sentence boundaries")
  void splitsSentences() {
    List<String> sentences =
        SentenceSplitter.split("Employees accrue leave. Carry-over is capped. Sickness is separate.");

    assertThat(sentences)
        .containsExactly(
            "Employees accrue leave.", "Carry-over is capped.", "Sickness is separate.");
  }

  @Test
  @DisplayName("does not split after a common abbreviation")
  void doesNotSplitOnAbbreviations() {
    assertThat(SentenceSplitter.split("See Dr. Smith for details."))
        .containsExactly("See Dr. Smith for details.");

    assertThat(SentenceSplitter.split("Several grades apply, e.g. grade 4 and above."))
        .containsExactly("Several grades apply, e.g. grade 4 and above.");

    assertThat(SentenceSplitter.split("Refer to Fig. 3 for the workflow."))
        .containsExactly("Refer to Fig. 3 for the workflow.");
  }

  @Test
  @DisplayName("does not split after a dotted acronym")
  void doesNotSplitOnDottedAcronyms() {
    assertThat(SentenceSplitter.split("This applies to U.S. employees only."))
        .containsExactly("This applies to U.S. employees only.");
  }

  @Test
  @DisplayName("does not split after a middle initial")
  void doesNotSplitOnInitials() {
    assertThat(SentenceSplitter.split("Contact J. Smith in HR."))
        .containsExactly("Contact J. Smith in HR.");
  }

  @Test
  @DisplayName("treats text without terminal punctuation as one sentence")
  void handlesUnterminatedText() {
    assertThat(SentenceSplitter.split("A heading without a full stop"))
        .containsExactly("A heading without a full stop");
  }

  @Test
  @DisplayName("returns nothing for blank input")
  void handlesBlankInput() {
    assertThat(SentenceSplitter.split(null)).isEmpty();
    assertThat(SentenceSplitter.split("")).isEmpty();
    assertThat(SentenceSplitter.split("   \n  ")).isEmpty();
  }

  @Test
  @DisplayName("preserves the original wording exactly")
  void preservesWording() {
    String text = "The policy applies to all permanent staff, pro rata (see section 4.2).";

    assertThat(SentenceSplitter.split(text)).containsExactly(text);
  }

  @Test
  @DisplayName("handles questions and exclamations")
  void handlesOtherTerminators() {
    assertThat(SentenceSplitter.split("Who approves leave? The line manager does! That is final."))
        .containsExactly(
            "Who approves leave?", "The line manager does!", "That is final.");
  }

  @Test
  @DisplayName("does not split inside a decimal or a section number")
  void doesNotSplitOnNumbers() {
    assertThat(SentenceSplitter.split("Section 3.1 covers this. It is short."))
        .containsExactly("Section 3.1 covers this.", "It is short.");
  }
}
