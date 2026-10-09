package com.example.ragpoc.retrieval;

import com.example.ragpoc.audit.AuditService;
import com.example.ragpoc.chat.AssistantMessages;
import com.example.ragpoc.config.RagProperties;
import com.example.ragpoc.port.EmbeddingPort;
import com.example.ragpoc.port.RerankerPort;
import com.example.ragpoc.port.SearchHit;
import com.example.ragpoc.port.VectorIndexPort;
import com.example.ragpoc.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The single point through which knowledge is read.
 *
 * <p>Everything that turns a question into citable evidence happens here, in the
 * order the specification lays out: inspect the input, embed, retrieve densely
 * and by keyword, fuse the two rankings, rerank, gate on relevance, then assemble
 * context. Two properties make this class worth being the only entry point:
 *
 * <ol>
 *   <li><b>Tenant verification.</b> Every returned chunk's {@code tenantId} is
 *       compared against the caller's, and a mismatch aborts the request and
 *       records a security event. The query already filters by tenant; this
 *       check is the independent second opinion, and it is the reason a filter
 *       bug produces a failed request rather than a wrong answer.
 *   <li><b>The gate is not the model's decision.</b> When the evidence is too
 *       weak, this class returns the refusal and no model call ever happens, so
 *       refusing is a property of the system rather than of a prompt that might
 *       be argued with.
 * </ol>
 */
public class RetrievalService {

  private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

  private final EmbeddingPort embeddingPort;
  private final VectorIndexPort index;
  private final RerankerPort reranker;
  private final InputGuard inputGuard;
  private final RrfFusion fusion;
  private final RelevanceGate gate;
  private final ContextAssembler assembler;
  private final AuditService audit;
  private final ExecutorService searchExecutor;
  private final RagProperties.Retrieval settings;

  public RetrievalService(
      EmbeddingPort embeddingPort,
      VectorIndexPort index,
      ObjectProvider<RerankerPort> rerankerProvider,
      InputGuard inputGuard,
      RrfFusion fusion,
      RelevanceGate gate,
      ContextAssembler assembler,
      AuditService audit,
      ExecutorService searchExecutor,
      RagProperties properties) {
    this.embeddingPort = embeddingPort;
    this.index = index;
    // The reranker can be disabled by configuration, which is useful for
    // measuring how much it actually contributes to answer quality.
    this.reranker = rerankerProvider.getIfAvailable();
    this.inputGuard = inputGuard;
    this.fusion = fusion;
    this.gate = gate;
    this.assembler = assembler;
    this.audit = audit;
    this.searchExecutor = searchExecutor;
    this.settings = properties.retrieval();
  }

  /**
   * Answers "what evidence exists, for this tenant, for this question".
   *
   * @return chunks to ground an answer in, or a refusal and the trace explaining it
   */
  public RetrievalOutcome retrieve(TenantContext tenant, String rawQuestion) {
    InputGuard.GuardResult guard = inputGuard.inspect(tenant, rawQuestion);
    if (!guard.allowed()) {
      if (guard.injectionSuspected()) {
        log.warn(
            "Refused a possible prompt-injection attempt from tenant {} (pattern: {})",
            tenant.tenantId(),
            InputGuard.matchedPattern(guard.question()));
        audit.record(tenant, AuditService.Type.SECURITY_ACCESS_DENIED, "prompt injection attempt");
      }
      return RetrievalOutcome.refused(
          emptyTrace(guard.question(), "input guard: " + guard.refusalMessage()),
          guard.refusalMessage());
    }

    String question = guard.question();

    long embedStart = System.nanoTime();
    float[] queryVector = embeddingPort.embed(question);
    long embedMillis = elapsed(embedStart);

    // Both searches run concurrently: they are independent, they hit the same
    // store, and running them in sequence would add their latencies together for
    // no reason.
    long searchStart = System.nanoTime();
    CompletableFuture<List<SearchHit>> dense =
        CompletableFuture.supplyAsync(
            () ->
                index.denseSearch(
                    tenant, queryVector, settings.denseCandidates(), VectorIndexPort.SearchFilter.none()),
            searchExecutor);
    CompletableFuture<List<SearchHit>> keyword =
        CompletableFuture.supplyAsync(
            () ->
                index.keywordSearch(
                    tenant, question, settings.keywordCandidates(), VectorIndexPort.SearchFilter.none()),
            searchExecutor);

    // Both durations are measured from the same start, so they read as "how long
    // until this search finished" with the other running alongside, not as a
    // sequential sum.
    long denseMillis;
    long keywordMillis;
    List<SearchHit> denseHits;
    List<SearchHit> keywordHits;
    try {
      denseHits = dense.join();
      denseMillis = elapsed(searchStart);
      keywordHits = keyword.join();
      keywordMillis = elapsed(searchStart);
    } catch (RuntimeException e) {
      // A failed search must not be answered from the half that succeeded: a
      // partial view of the evidence is exactly the situation in which the model
      // would guess.
      log.error("Retrieval failed for tenant {}", tenant.tenantId(), e);
      return RetrievalOutcome.refused(
          emptyTrace(question, "retrieval failed: " + e.getClass().getSimpleName()),
          AssistantMessages.GENERIC_ERROR);
    }

    // The independent tenant check, on the data that actually came back.
    verifyTenant(tenant, denseHits, "dense");
    verifyTenant(tenant, keywordHits, "keyword");

    List<RrfFusion.FusedHit> fused = fusion.fuse(denseHits, keywordHits, settings.fusedCandidates());

    if (fused.isEmpty()) {
      return RetrievalOutcome.refused(
          trace(
              question,
              "refused",
              "no candidates retrieved",
              denseHits,
              keywordHits,
              List.of(),
              Double.NEGATIVE_INFINITY,
              0,
              embedMillis,
              denseMillis,
              keywordMillis,
              0),
          AssistantMessages.REFUSAL);
    }

    long rerankStart = System.nanoTime();
    List<Candidate> candidates = scoreCandidates(question, fused);
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
              denseHits,
              keywordHits,
              candidates,
              decision.bestScore(),
              0,
              embedMillis,
              denseMillis,
              keywordMillis,
              rerankMillis),
          AssistantMessages.REFUSAL);
    }

    // Rank by the reranker, then drop everything under the per-chunk floor so
    // weak context cannot dilute the prompt.
    List<Candidate> aboveFloor =
        candidates.stream()
            .filter(candidate -> candidate.rerankScore() >= gate.minChunkRelevance())
            .sorted(Comparator.comparingDouble(Candidate::rerankScore).reversed())
            .limit(settings.rerankTopN())
            .toList();

    ContextAssembler.AssemblyResult assembly = assembler.assemble(aboveFloor);

    if (assembly.chunks().isEmpty()) {
      return RetrievalOutcome.refused(
          trace(
              question,
              "refused",
              "no candidate survived context assembly",
              denseHits,
              keywordHits,
              candidates,
              decision.bestScore(),
              0,
              embedMillis,
              denseMillis,
              keywordMillis,
              rerankMillis),
          AssistantMessages.REFUSAL);
    }

    return RetrievalOutcome.answerable(
        assembly.chunks(),
        trace(
            question,
            "answer",
            decision.reason(),
            denseHits,
            keywordHits,
            candidates,
            decision.bestScore(),
            assembly.chunks().size(),
            embedMillis,
            denseMillis,
            keywordMillis,
            rerankMillis));
  }

  /** Runs the reranker, or passes the fused score through when it is disabled. */
  private List<Candidate> scoreCandidates(String question, List<RrfFusion.FusedHit> fused) {
    if (reranker == null) {
      return fused.stream()
          .map(hit -> new Candidate(hit.hit(), hit.denseScore(), hit.keywordScore(), hit.rrfScore(), 0.0))
          .toList();
    }

    List<String> passages = fused.stream().map(fusedHit -> fusedHit.hit().text()).toList();
    double[] scores = reranker.score(question, passages);

    List<Candidate> candidates = new ArrayList<>(fused.size());
    for (int i = 0; i < fused.size(); i++) {
      RrfFusion.FusedHit hit = fused.get(i);
      candidates.add(
          new Candidate(hit.hit(), hit.denseScore(), hit.keywordScore(), hit.rrfScore(), scores[i]));
    }
    return candidates;
  }

  /**
   * Asserts that nothing from another tenant came back.
   *
   * <p>Deliberately checked on the returned rows rather than trusted from the
   * query, and deliberately fatal: a mismatch means the filter did not do its
   * job, and continuing would mean answering from another organisation's
   * documents.
   */
  private void verifyTenant(TenantContext tenant, List<SearchHit> hits, String source) {
    for (SearchHit hit : hits) {
      if (!tenant.tenantId().equals(hit.tenantId())) {
        audit.record(
            tenant,
            AuditService.Type.SECURITY_TENANT_MISMATCH,
            "chunk "
                + hit.chunkId()
                + " retrieved by "
                + source
                + " search belongs to tenant "
                + hit.tenantId());
        throw new TenantMismatchException(
            "Retrieved a chunk belonging to another tenant from the "
                + source
                + " search; aborting the request");
      }
    }
  }

  private RetrievalTrace trace(
      String question,
      String decision,
      String reason,
      List<SearchHit> dense,
      List<SearchHit> keyword,
      List<Candidate> candidates,
      double bestRerankScore,
      int selectedCount,
      long embedMillis,
      long denseMillis,
      long keywordMillis,
      long rerankMillis) {

    var denseRanks = RrfFusion.ranks(dense);
    var keywordRanks = RrfFusion.ranks(keyword);

    List<RetrievalTrace.CandidateTrace> candidateTraces = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      Candidate candidate = candidates.get(i);
      boolean selected = i < selectedCount;
      candidateTraces.add(
          new RetrievalTrace.CandidateTrace(
              candidate.hit().chunkId(),
              candidate.hit().documentId(),
              candidate.hit().docTitle(),
              candidate.hit().pageStart(),
              candidate.hit().pageEnd(),
              doubleOrNull(denseRanks.get(candidate.hit().chunkId())),
              doubleOrNull(keywordRanks.get(candidate.hit().chunkId())),
              candidate.rrfScore(),
              candidate.rerankScore(),
              selected,
              selected ? null : "not selected"));
    }

    return new RetrievalTrace(
        question,
        decision,
        reason,
        dense.size(),
        keyword.size(),
        candidates.size(),
        candidates.size(),
        selectedCount,
        bestRerankScore,
        gate.minRelevance(),
        gate.minChunkRelevance(),
        embeddingPort.modelId(),
        reranker == null ? "none" : reranker.modelId(),
        embedMillis,
        denseMillis,
        keywordMillis,
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
        0,
        0,
        Double.NEGATIVE_INFINITY,
        gate.minRelevance(),
        gate.minChunkRelevance(),
        embeddingPort.modelId(),
        reranker == null ? "none" : reranker.modelId(),
        0,
        0,
        0,
        0,
        List.of(),
        List.of());
  }

  private static Double doubleOrNull(Integer value) {
    return value == null ? null : value.doubleValue();
  }

  private static long elapsed(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /** Convenience for callers that only care whether anything was found. */
  public boolean hasEvidence(TenantContext tenant, String question) {
    return retrieve(tenant, question).canAnswer();
  }

  /** Exposed for diagnostics; the tenant is required like everywhere else. */
  public List<RetrievedChunk> chunksFor(TenantContext tenant, UUID documentId, String question) {
    return retrieve(tenant, question).chunks().stream()
        .filter(chunk -> chunk.documentId().equals(documentId))
        .toList();
  }
}
