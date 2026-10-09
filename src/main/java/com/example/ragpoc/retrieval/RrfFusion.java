package com.example.ragpoc.retrieval;

import com.example.ragpoc.port.SearchHit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merges the dense and keyword result lists with Reciprocal Rank Fusion.
 *
 * <p>RRF is used rather than combining the raw scores because the two are not
 * commensurable: a cosine similarity in [-1, 1] and a {@code ts_rank_cd} value
 * with no fixed upper bound cannot be added or averaged without inventing a
 * weighting that would have to be re-tuned for every embedding model and corpus.
 * RRF only uses each result's <em>rank</em>, so a document that both retrievers
 * rank highly wins, and neither retriever's score scale can dominate.
 *
 * <p>{@code 1 / (k + rank)} with {@code k = 60} is the standard constant: it
 * flattens the influence of the very top ranks, which keeps a single retriever's
 * confident-but-wrong first result from crowding out agreement further down.
 */
public class RrfFusion {

  private final int k;

  public RrfFusion(int k) {
    if (k <= 0) {
      throw new IllegalArgumentException("RRF k must be positive");
    }
    this.k = k;
  }

  /**
   * A fused candidate.
   *
   * @param denseScore similarity from the vector search, or null when the
   *     candidate came only from keyword search
   * @param keywordScore rank score from full-text search, or null when the
   *     candidate came only from vector search
   */
  public record FusedHit(SearchHit hit, double rrfScore, Double denseScore, Double keywordScore) {}

  /**
   * Fuses two ranked lists.
   *
   * <p>Order within each input list is taken to be its ranking; ties are broken
   * by the order supplied, which the adapters make deterministic.
   */
  public List<FusedHit> fuse(List<SearchHit> dense, List<SearchHit> keyword, int limit) {
    Map<UUID, Accumulator> byChunkId = new LinkedHashMap<>();

    // Dense first, then keyword: both feed the same accumulator, and insertion
    // order gives a stable tie-break when scores are identical.
    accumulate(byChunkId, dense, true);
    accumulate(byChunkId, keyword, false);

    List<FusedHit> fused = new ArrayList<>(byChunkId.size());
    for (Accumulator accumulator : byChunkId.values()) {
      fused.add(
          new FusedHit(
              accumulator.hit, accumulator.rrfScore, accumulator.denseScore, accumulator.keywordScore));
    }

    fused.sort(
        java.util.Comparator.comparingDouble(RrfFusion.FusedHit::rrfScore)
            .reversed()
            // A deterministic final tie-break matters: without it the same query
            // could rank equally scored chunks differently between runs, which
            // would make the evaluation harness irreproducible.
            .thenComparing(hit -> hit.hit().chunkId().toString()));

    return fused.size() <= limit ? fused : new ArrayList<>(fused.subList(0, limit));
  }

  private void accumulate(Map<UUID, Accumulator> byChunkId, List<SearchHit> hits, boolean dense) {
    for (int rank = 0; rank < hits.size(); rank++) {
      SearchHit hit = hits.get(rank);
      double contribution = 1.0 / (k + rank + 1); // rank is 1-based in the formula
      Accumulator accumulator =
          byChunkId.computeIfAbsent(hit.chunkId(), id -> new Accumulator(hit));
      accumulator.rrfScore += contribution;
      if (dense) {
        accumulator.denseScore = hit.score();
      } else {
        accumulator.keywordScore = hit.score();
      }
    }
  }

  private static final class Accumulator {
    private final SearchHit hit;
    private double rrfScore;
    private Double denseScore;
    private Double keywordScore;

    private Accumulator(SearchHit hit) {
      this.hit = hit;
    }
  }

  /** Rank positions, for the retrieval trace. */
  public static Map<UUID, Integer> ranks(List<SearchHit> hits) {
    Map<UUID, Integer> ranks = new HashMap<>();
    for (int i = 0; i < hits.size(); i++) {
      ranks.putIfAbsent(hits.get(i).chunkId(), i + 1);
    }
    return ranks;
  }
}
