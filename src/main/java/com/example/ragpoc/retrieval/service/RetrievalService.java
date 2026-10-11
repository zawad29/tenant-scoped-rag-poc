package com.example.ragpoc.retrieval.service;

import com.example.ragpoc.audit.entity.AuditEvent;
import com.example.ragpoc.audit.service.AuditService;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.config.RagProperties;
import com.example.ragpoc.retrieval.AssistantMessages;
import com.example.ragpoc.retrieval.CidMismatchException;
import com.example.ragpoc.retrieval.model.Candidate;
import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.retrieval.model.RetrievalTrace;
import com.example.ragpoc.retrieval.model.RetrievedChunk;
import com.example.ragpoc.retrieval.model.SearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * The single point through which knowledge is read.
 *
 * <p>Order: guard, embed, dense search, verify the returned cids, rerank, gate,
 * assemble. Two properties make this worth being the only entry point:
 *
 * <ol>
 *   <li><b>Cid verification.</b> Every returned chunk's cid is compared against
 *       the caller's; a mismatch aborts the request and writes a security event.
 *       The store already filters; this is the independent second opinion.
 *   <li><b>The gate is not the model's decision.</b> When the evidence is too
 *       weak, this class returns the refusal and no model call happens.
 * </ol>
 */
@Slf4j
public class RetrievalService {

  private final EmbeddingService embedding;
  private final QdrantVectorStore index;
  private final RerankerService reranker; // null when reranking is disabled
  private final InputGuard inputGuard;
  private final RelevanceGate gate;
  private final ContextAssembler assembler;
  private final AuditService audit;
  private final RagProperties.Retrieval settings;

  public RetrievalService(
      EmbeddingService embedding,
      QdrantVectorStore index,
      RerankerService reranker,
      InputGuard inputGuard,
      RelevanceGate gate,
      ContextAssembler assembler,
      AuditService audit,
      RagProperties properties) {
    this.embedding = embedding;
    this.index = index;
    this.reranker = reranker;
    this.inputGuard = inputGuard;
    this.gate = gate;
    this.assembler = assembler;
    this.audit = audit;
    this.settings = properties.retrieval();
  }

  public RetrievalOutcome retrieve(CidContext cid, String rawQuestion) {
    InputGuard.GuardResult guard = inputGuard.inspect(cid, rawQuestion);
    if (!guard.allowed()) {
      if (guard.injectionSuspected()) {
        log.warn("Refused a possible prompt-injection attempt from cid {}", cid.cidId());
        audit.record(
            cid.cidId(), cid.userId(), AuditEvent.Type.SECURITY_ACCESS_DENIED, "prompt injection attempt");
      }
      return RetrievalOutcome.refused(
          emptyTrace(guard.question(), "input guard: " + guard.refusalMessage()),
          guard.refusalMessage());
    }

    String question = guard.question();

    long embedStart = System.nanoTime();
    float[] queryVector = embedding.embed(question);
    long embedMillis = elapsed(embedStart);

    long searchStart = System.nanoTime();
    List<SearchHit> hits;
    try {
      hits = index.denseSearch(cid, queryVector, settings.denseCandidates(), null);
    } catch (RuntimeException e) {
      log.error("Retrieval failed for cid {}", cid.cidId(), e);
      return RetrievalOutcome.refused(
          emptyTrace(question, "retrieval failed: " + e.getClass().getSimpleName()),
          AssistantMessages.GENERIC_ERROR);
    }
    long searchMillis = elapsed(searchStart);

    // The independent cid check, on the data that actually came back.
    verifyCid(cid, hits);

    if (hits.isEmpty()) {
      return RetrievalOutcome.refused(
          trace(
              question,
              "refused",
              "no candidates retrieved",
              hits,
              List.of(),
              List.of(),
              Double.NEGATIVE_INFINITY,
              embedMillis,
              searchMillis,
              0),
          AssistantMessages.REFUSAL);
    }

    long rerankStart = System.nanoTime();
    List<Candidate> candidates = scoreCandidates(question, hits);
    long rerankMillis = elapsed(rerankStart);

    double[] rerankScores = candidates.stream().mapToDouble(Candidate::rerankScore).toArray();
    RelevanceGate.GateDecision decision =
        reranker == null ? gate.evaluateWithoutReranker(candidates.size()) : gate.evaluate(rerankScores);

    if (!decision.answer()) {
      return RetrievalOutcome.refused(
          trace(
              question,
              "refused",
              decision.reason(),
              hits,
              candidates,
              List.of(),
              decision.bestScore(),
              embedMillis,
              searchMillis,
              rerankMillis),
          AssistantMessages.REFUSAL);
    }

    List<Candidate> ranked = rankForContext(candidates);

    ContextAssembler.AssemblyResult assembly = assembler.assemble(ranked);

    if (assembly.chunks().isEmpty()) {
      return RetrievalOutcome.refused(
          trace(
              question,
              "refused",
              "no candidate survived context assembly",
              hits,
              candidates,
              List.of(),
              decision.bestScore(),
              embedMillis,
              searchMillis,
              rerankMillis),
          AssistantMessages.REFUSAL);
    }

    return RetrievalOutcome.answerable(
        assembly.chunks(),
        trace(
            question,
            "answer",
            decision.reason(),
            hits,
            candidates,
            assembly.chunks(),
            decision.bestScore(),
            embedMillis,
            searchMillis,
            rerankMillis));
  }

  private List<Candidate> scoreCandidates(String question, List<SearchHit> hits) {
    if (reranker == null) {
      return hits.stream().map(hit -> new Candidate(hit, 0.0)).toList();
    }
    List<String> passages = hits.stream().map(SearchHit::text).toList();
    double[] scores = reranker.score(question, passages);

    List<Candidate> candidates = new ArrayList<>(hits.size());
    for (int i = 0; i < hits.size(); i++) {
      candidates.add(new Candidate(hits.get(i), scores[i]));
    }
    return candidates;
  }

  private List<Candidate> rankForContext(List<Candidate> candidates) {
    if (reranker == null) {
      // No absolute floor exists in degraded mode: order by dense similarity.
      return candidates.stream()
          .sorted(Comparator.comparingDouble((Candidate c) -> c.hit().score()).reversed())
          .limit(settings.rerankTopN())
          .toList();
    }
    return candidates.stream()
        .filter(candidate -> candidate.rerankScore() >= gate.minChunkRelevance())
        .sorted(Comparator.comparingDouble(Candidate::rerankScore).reversed())
        .limit(settings.rerankTopN())
        .toList();
  }

  /** Asserts that nothing from another cid came back. Deliberately fatal. */
  private void verifyCid(CidContext cid, List<SearchHit> hits) {
    for (SearchHit hit : hits) {
      if (!cid.cidId().equals(hit.cidId())) {
        audit.record(
            cid.cidId(),
            cid.userId(),
            AuditEvent.Type.SECURITY_CID_MISMATCH,
            "chunk " + hit.chunkId() + " retrieved for cid " + cid.cidId() + " belongs to cid " + hit.cidId());
        throw new CidMismatchException(
            "Retrieved a chunk belonging to another cid; aborting the request");
      }
    }
  }

  private RetrievalTrace trace(
      String question,
      String decision,
      String reason,
      List<SearchHit> hits,
      List<Candidate> candidates,
      List<RetrievedChunk> selected,
      double bestRerankScore,
      long embedMillis,
      long searchMillis,
      long rerankMillis) {

    java.util.Set<UUID> selectedIds = new java.util.HashSet<>();
    for (RetrievedChunk chunk : selected) {
      selectedIds.add(chunk.chunkId());
    }

    // Rerank rank: position when the candidates are ordered by reranker score.
    Integer[] rerankRanks = null;
    if (reranker != null) {
      Integer[] order = new Integer[candidates.size()];
      for (int i = 0; i < candidates.size(); i++) {
        order[i] = i;
      }
      java.util.Arrays.sort(
          order,
          Comparator.comparingDouble((Integer i) -> candidates.get(i).rerankScore()).reversed());
      rerankRanks = new Integer[candidates.size()];
      for (int rank = 0; rank < order.length; rank++) {
        rerankRanks[order[rank]] = rank + 1;
      }
    }

    List<RetrievalTrace.CandidateTrace> candidateTraces = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      Candidate candidate = candidates.get(i);
      boolean isSelected = selectedIds.contains(candidate.hit().chunkId());
      candidateTraces.add(
          new RetrievalTrace.CandidateTrace(
              candidate.hit().chunkId(),
              candidate.hit().documentId(),
              candidate.hit().docTitle(),
              candidate.hit().pageStart(),
              candidate.hit().pageEnd(),
              candidate.hit().score(),
              reranker == null ? null : candidate.rerankScore(),
              i + 1,
              rerankRanks == null ? null : rerankRanks[i],
              isSelected,
              isSelected ? null : "not selected"));
    }

    return new RetrievalTrace(
        question,
        decision,
        reason,
        hits.size(),
        candidates.size(),
        selectedIds.size(),
        bestRerankScore,
        gate.minRelevance(),
        gate.minChunkRelevance(),
        embedding.modelId(),
        reranker == null ? "none" : reranker.modelId(),
        embedMillis,
        searchMillis,
        rerankMillis,
        candidateTraces,
        List.of());
  }

  private RetrievalTrace emptyTrace(String question, String reason) {
    return new RetrievalTrace(
        question,
        "refused",
        reason,
        0,
        0,
        0,
        Double.NEGATIVE_INFINITY,
        gate.minRelevance(),
        gate.minChunkRelevance(),
        embedding.modelId(),
        reranker == null ? "none" : reranker.modelId(),
        0,
        0,
        0,
        List.of(),
        List.of());
  }

  private static long elapsed(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /** Convenience for callers that only care whether anything was found. */
  public boolean hasEvidence(CidContext cid, String question) {
    return retrieve(cid, question).canAnswer();
  }

  /** Exposed for diagnostics; the cid is required like everywhere else. */
  public List<RetrievedChunk> chunksFor(CidContext cid, UUID documentId, String question) {
    return retrieve(cid, question).chunks().stream()
        .filter(chunk -> chunk.documentId().equals(documentId))
        .toList();
  }
}
