package com.example.ragpoc.config;

import com.example.ragpoc.audit.AuditService;
import com.example.ragpoc.ingest.TokenCounter;
import com.example.ragpoc.port.EmbeddingPort;
import com.example.ragpoc.port.RerankerPort;
import com.example.ragpoc.port.VectorIndexPort;
import com.example.ragpoc.retrieval.ContextAssembler;
import com.example.ragpoc.retrieval.InputGuard;
import com.example.ragpoc.retrieval.RelevanceGate;
import com.example.ragpoc.retrieval.RetrievalService;
import com.example.ragpoc.retrieval.RrfFusion;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the retrieval pipeline.
 *
 * <p>The components are separate objects rather than private methods of
 * {@link RetrievalService} so that the fusion, the gate and the assembler can be
 * tested directly, at the level where their behaviour is actually decided.
 */
@Configuration
public class RetrievalConfiguration {

  @Bean
  public InputGuard inputGuard(RagProperties properties) {
    return new InputGuard(properties);
  }

  @Bean
  public RrfFusion rrfFusion(RagProperties properties) {
    return new RrfFusion(properties.retrieval().rrfK());
  }

  @Bean
  public RelevanceGate relevanceGate(RagProperties properties) {
    return new RelevanceGate(
        properties.retrieval().minRelevance(), properties.retrieval().minChunkRelevance());
  }

  @Bean
  public ContextAssembler contextAssembler(TokenCounter tokenCounter, RagProperties properties) {
    return new ContextAssembler(tokenCounter, properties.retrieval().contextTokenBudget());
  }

  /**
   * Executor for the two concurrent searches.
   *
   * <p>Virtual threads: the work is a blocking database call, which is exactly
   * what they are for, and it keeps the pool from becoming a fixed bottleneck
   * that would serialise requests under load.
   */
  @Bean(destroyMethod = "")
  public ExecutorService retrievalExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  public RetrievalService retrievalService(
      EmbeddingPort embeddingPort,
      VectorIndexPort index,
      ObjectProvider<RerankerPort> reranker,
      InputGuard inputGuard,
      RrfFusion rrfFusion,
      RelevanceGate relevanceGate,
      ContextAssembler contextAssembler,
      AuditService audit,
      ExecutorService retrievalExecutor,
      RagProperties properties) {
    return new RetrievalService(
        embeddingPort,
        index,
        reranker,
        inputGuard,
        rrfFusion,
        relevanceGate,
        contextAssembler,
        audit,
        retrievalExecutor,
        properties);
  }
}
