package com.example.ragpoc.retrieval.service;

import com.example.ragpoc.ingestion.chunk.TokenCounter;
import com.example.ragpoc.retrieval.model.Candidate;
import com.example.ragpoc.retrieval.model.RetrievedChunk;
import com.example.ragpoc.retrieval.rerank.ScoringScale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the context that goes into the prompt.
 *
 * <p>Removes near-identical passages, caps by tokens, and assigns stable
 * {@code S1}, {@code S2}, ... identifiers so a citation can be checked against
 * the sources that were actually supplied.
 */
@Slf4j
public class ContextAssembler {

  private final TokenCounter tokenCounter;
  private final int tokenBudget;
  private final ScoringScale scoringScale;

  public ContextAssembler(TokenCounter tokenCounter, int tokenBudget, ScoringScale scoringScale) {
    this.tokenCounter = tokenCounter;
    this.tokenBudget = tokenBudget;
    this.scoringScale = scoringScale;
  }

  public record AssemblyResult(List<RetrievedChunk> chunks, List<Dropped> dropped) {}

  public record Dropped(Candidate candidate, String reason) {}

  public AssemblyResult assemble(List<Candidate> ranked) {
    log.info(
        "retrieve step=assemble-start candidates={} tokenBudget={}", ranked.size(), tokenBudget);
    List<RetrievedChunk> selected = new ArrayList<>();
    List<Dropped> dropped = new ArrayList<>();
    Set<String> seenText = new HashSet<>();
    int usedTokens = 0;

    for (Candidate candidate : ranked) {
      String normalised = normaliseForDuplicateCheck(candidate.hit().text());

      if (!seenText.add(normalised)) {
        dropped.add(new Dropped(candidate, "duplicate of an already selected chunk"));
        log.debug(
            "retrieve step=assemble drop chunk={} reason=duplicate", candidate.hit().chunkId());
        continue;
      }

      int tokens = tokenCounter.count(candidate.hit().text());
      if (usedTokens + tokens > tokenBudget) {
        dropped.add(
            new Dropped(
                candidate,
                "context token budget of %d would be exceeded (%d used, %d needed)"
                    .formatted(tokenBudget, usedTokens, tokens)));
        log.debug(
            "retrieve step=assemble drop chunk={} reason=token-budget used={} needed={} budget={}",
            candidate.hit().chunkId(), usedTokens, tokens, tokenBudget);
        continue;
      }

      usedTokens += tokens;
      selected.add(toRetrievedChunk(selected.size(), candidate));
      log.debug(
          "retrieve step=assemble select source=S{} chunk={} tokens={} used={}",
          selected.size(), candidate.hit().chunkId(), tokens, usedTokens);
    }

    log.info(
        "retrieve step=assemble-done selected={} dropped={} usedTokens={}",
        selected.size(), dropped.size(), usedTokens);
    return new AssemblyResult(List.copyOf(selected), List.copyOf(dropped));
  }

  private RetrievedChunk toRetrievedChunk(int index, Candidate candidate) {
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
        hit.score(),
        candidate.rerankScore(),
        RelevanceGate.relevanceFor(scoringScale, candidate.rerankScore()));
  }

  private static String normaliseForDuplicateCheck(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  public int tokenBudget() {
    return tokenBudget;
  }
}
