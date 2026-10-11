package com.example.ragpoc.config;

import com.example.ragpoc.audit.service.AuditService;
import com.example.ragpoc.ingestion.chunk.DocumentChunker;
import com.example.ragpoc.ingestion.chunk.HfTokenCounter;
import com.example.ragpoc.ingestion.chunk.TextCleaner;
import com.example.ragpoc.ingestion.chunk.TokenCounter;
import com.example.ragpoc.ingestion.pdf.PdfPageExtractor;
import com.example.ragpoc.retrieval.onnx.OnnxTextModel;
import com.example.ragpoc.retrieval.onnx.PoolingMode;
import com.example.ragpoc.retrieval.service.ContextAssembler;
import com.example.ragpoc.retrieval.service.EmbeddingService;
import com.example.ragpoc.retrieval.service.InputGuard;
import com.example.ragpoc.retrieval.service.QdrantVectorStore;
import com.example.ragpoc.retrieval.service.RerankerService;
import com.example.ragpoc.retrieval.service.RetrievalService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires configuration to the concrete pipeline components. The ONNX models are
 * loaded once at startup and closed on shutdown; loading them lazily would move a
 * 400 MB read into the first user's request.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class AppConfiguration {

  @Bean(destroyMethod = "close")
  @Lazy
  public OnnxTextModel embeddingModel(RagProperties properties) throws IOException {
    RagProperties.Embedding embedding = properties.embedding();
    return OnnxTextModel.load(
        Path.of(embedding.modelPath()),
        Path.of(embedding.tokenizerPath()),
        embedding.maxSequenceLength(),
        4);
  }

  @Bean(destroyMethod = "close")
  @Lazy
  @ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "true", matchIfMissing = true)
  public OnnxTextModel rerankerModel(RagProperties properties) throws IOException {
    RagProperties.Rerank rerank = properties.rerank();
    return OnnxTextModel.load(
        Path.of(rerank.modelPath()), Path.of(rerank.tokenizerPath()), rerank.maxSequenceLength(), 4);
  }

  @Bean(destroyMethod = "close")
  @Lazy
  public TokenCounter tokenCounter(RagProperties properties) throws IOException {
    // The embedding model's tokenizer, so chunk sizes are measured in the unit
    // the embedding context window uses.
    return new HfTokenCounter(Path.of(properties.embedding().tokenizerPath()));
  }

  @Bean
  @Lazy
  public EmbeddingService embeddingService(OnnxTextModel embeddingModel, RagProperties properties) {
    RagProperties.Embedding embedding = properties.embedding();
    return new EmbeddingService(
        embeddingModel,
        embedding.modelId(),
        parsePoolingMode(embedding.pooling()),
        embedding.normalize(),
        embedding.dimension(),
        embedding.batchSize());
  }

  @Bean
  @Lazy
  @ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "true", matchIfMissing = true)
  public RerankerService rerankerService(OnnxTextModel rerankerModel, RagProperties properties) {
    return new RerankerService(
        rerankerModel, properties.rerank().modelId(), properties.rerank().batchSize());
  }

  @Bean(destroyMethod = "close")
  public QdrantVectorStore qdrantVectorStore(RagProperties properties) {
    return new QdrantVectorStore(properties.qdrant(), properties.embedding().dimension());
  }

  @Bean
  public PdfPageExtractor pdfPageExtractor(RagProperties properties) {
    return new PdfPageExtractor(
        properties.ingestion().maxFileBytes(), properties.ingestion().maxPages());
  }

  @Bean
  public TextCleaner textCleaner() {
    return new TextCleaner();
  }

  @Bean
  @Lazy
  public DocumentChunker documentChunker(TokenCounter tokenCounter, RagProperties properties) {
    RagProperties.Chunking chunking = properties.chunking();
    return new DocumentChunker(
        tokenCounter,
        chunking.targetTokens(),
        chunking.maxTokens(),
        chunking.minTokens(),
        chunking.overlapPercent());
  }

  @Bean
  public InputGuard inputGuard(RagProperties properties) {
    return new InputGuard(properties);
  }

  @Bean
  public com.example.ragpoc.retrieval.service.RelevanceGate relevanceGate(RagProperties properties) {
    return new com.example.ragpoc.retrieval.service.RelevanceGate(
        properties.retrieval().minRelevance(), properties.retrieval().minChunkRelevance());
  }

  @Bean
  @Lazy
  public ContextAssembler contextAssembler(TokenCounter tokenCounter, RagProperties properties) {
    return new ContextAssembler(tokenCounter, properties.retrieval().contextTokenBudget());
  }

  @Bean
  @Lazy
  public RetrievalService retrievalService(
      EmbeddingService embeddingService,
      QdrantVectorStore vectorStore,
      ObjectProvider<RerankerService> rerankerProvider,
      InputGuard inputGuard,
      com.example.ragpoc.retrieval.service.RelevanceGate relevanceGate,
      ContextAssembler contextAssembler,
      AuditService audit,
      RagProperties properties) {
    return new RetrievalService(
        embeddingService,
        vectorStore,
        rerankerProvider.getIfAvailable(),
        inputGuard,
        relevanceGate,
        contextAssembler,
        audit,
        properties);
  }

  private static PoolingMode parsePoolingMode(String configured) {
    try {
      return PoolingMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalStateException(
          "Unrecognised rag.embedding.pooling value '"
              + configured
              + "'. Use CLS for bge models and MEAN for sentence-transformers models.",
          e);
    }
  }
}
