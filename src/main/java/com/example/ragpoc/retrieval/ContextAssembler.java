package com.example.ragpoc.retrieval;

import com.example.ragpoc.ingest.TokenCounter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the context that goes into the prompt.
 *
 * <p>Three jobs, each of which protects the answer in a different way:
 * removing near-identical passages so one repeated sentence cannot fill the
 * context, capping by tokens so the prompt stays inside the model's window, and
 * assigning stable {@code S1}, {@code S2}, ... identifiers so a citation can be
 * checked against the sources that were actually supplied.
 */
public class ContextAssembler {

  private final TokenCounter tokenCounter;
  private final int tokenBudget;

  public ContextAssembler(TokenCounter tokenCounter, int tokenBudget) {
    this.tokenCounter = tokenCounter;
    this.tokenBudget = tokenBudget;
  }

  /**
   * Result of assembly.
   *
   * @param dropped records why each excluded candidate was excluded
   */
  public record AssemblyResult(List<RetrievedChunk> chunks, List<Dropped> dropped) {}

  /** A candidate that did not make it into the prompt, and why. */
  public record Dropped(Candidate candidate, String reason) {}

  /**
   * Selects the highest-ranked candidates that fit the budget.
   *
   * @param ranked candidates in descending relevance order
   */
  public AssemblyResult assemble(List<Candidate> ranked) {
    List<RetrievedChunk> selected = new ArrayList<>();
    List<Dropped> dropped = new ArrayList<>();
    Set<String> seenText = new HashSet<>();
    int usedTokens = 0;

    for (Candidate candidate : ranked) {
      String normalised = normaliseForDuplicateCheck(candidate.hit().text());

      // An exact-normalised duplicate is dropped rather than summarised. Near
      // duplicates that differ by a word are kept: overlap between neighbouring
      // chunks is deliberate, and collapsing similar-but-not-identical passages
      // risks discarding the one that actually answers the question.
      if (!seenText.add(normalised)) {
        dropped.add(new Dropped(candidate, "duplicate of an already selected chunk"));
        continue;
      }

      int tokens = tokenCounter.count(candidate.hit().text());
      if (usedTokens + tokens > tokenBudget) {
        dropped.add(
            new Dropped(
                candidate,
                "context token budget of %d would be exceeded (%d used, %d needed)"
                    .formatted(tokenBudget, usedTokens, tokens)));
        continue;
      }

      usedTokens += tokens;
      selected.add(toRetrievedChunk(selected.size(), candidate));
    }

    return new AssemblyResult(List.copyOf(selected), List.copyOf(dropped));
  }

  private static RetrievedChunk toRetrievedChunk(int index, Candidate candidate) {
    var hit = candidate.hit();
    return new RetrievedChunk(
        "S" + (index + 1),
        hit.chunkId(),
        hit.documentId(),
        hit.version(),
        hit.pageStart(),
        hit.pageEnd(),
        hit.sectionTitle(),
        hit.docTitle(),
        hit.text(),
        candidate.denseScore(),
        candidate.keywordScore(),
        candidate.rrfScore(),
        candidate.rerankScore(),
        RelevanceGate.asRelevance(candidate.rerankScore()));
  }

  private static String normaliseForDuplicateCheck(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  public int tokenBudget() {
    return tokenBudget;
  }
}
