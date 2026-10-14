package com.example.ragpoc.config;

import com.example.ragpoc.audit.service.AuditService;
import com.example.ragpoc.chat.service.ChatService;
import com.example.ragpoc.cid.service.CidService;
import com.example.ragpoc.ingestion.chunk.DocumentChunker;
import com.example.ragpoc.ingestion.chunk.HfTokenCounter;
import com.example.ragpoc.ingestion.chunk.TextCleaner;
import com.example.ragpoc.ingestion.chunk.TokenCounter;
import com.example.ragpoc.ingestion.tika.TikaTextExtractor;
import com.example.ragpoc.retrieval.embedding.ContextualEmbeddingModel;
import com.example.ragpoc.retrieval.onnx.OnnxCrossEncoder;
import com.example.ragpoc.retrieval.rerank.DenseOnlyReranker;
import com.example.ragpoc.retrieval.rerank.OnnxReranker;
import com.example.ragpoc.retrieval.rerank.Reranker;
import com.example.ragpoc.retrieval.search.CidScopedVectorSearch;
import com.example.ragpoc.retrieval.search.DocumentIndex;
import com.example.ragpoc.retrieval.service.ContextAssembler;
import com.example.ragpoc.retrieval.service.InputGuard;
import com.example.ragpoc.retrieval.service.RelevanceGate;
import com.example.ragpoc.retrieval.service.RetrievalService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.nio.file.Path;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;

/**
 * Wires configuration to the concrete pipeline components.
 *
 * <p>Embedding and reranking are behind provider-neutral interfaces
 * ({@link EmbeddingModel} and {@link Reranker}); this class is the only place
 * that names the local ONNX implementations, so a hosted service can replace
 * them without touching the pipeline.
 *
 * <p>The heavy ML beans are {@code @Lazy}: the application can start, serve
 * static pages and accept logins without the ~200 MB of local models, and a
 * missing model surfaces on the first ingest or question rather than at boot.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class AppConfiguration {

  // --- embedding ------------------------------------------------------------

  @Bean(destroyMethod = "close")
  @Lazy
  public EmbeddingModel embeddingModel(RagProperties properties) throws Exception {
    RagProperties.Transformers transformers = properties.embedding().transformers();

    TransformersEmbeddingModel model = new TransformersEmbeddingModel();
    model.setDisableCaching(true);
    model.setModelResource(new FileSystemResource(Path.of(transformers.modelPath())));
    model.setTokenizerResource(new FileSystemResource(Path.of(transformers.tokenizerPath())));
    model.setModelOutputName(transformers.outputName());
    model.afterPropertiesSet();

    // The decorator adds the document-context prefix while embedding; it is the
    // provider-neutral bean the vector store depends on.
    return new ContextualEmbeddingModel(model);
  }

  // --- vector store ---------------------------------------------------------

  @Bean(destroyMethod = "close")
  public QdrantClient qdrantClient(RagProperties properties) {
    RagProperties.Qdrant qdrant = properties.qdrant();
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(qdrant.host(), qdrant.port(), qdrant.useTls());
    if (qdrant.apiKey() != null && !qdrant.apiKey().isBlank()) {
      builder.withApiKey(qdrant.apiKey());
    }
    return new QdrantClient(builder.build());
  }

  @Bean
  @Lazy
  public VectorStore vectorStore(
      QdrantClient qdrantClient, EmbeddingModel embeddingModel, RagProperties properties) {
    return QdrantVectorStore.builder(qdrantClient, embeddingModel)
        .collectionName(properties.qdrant().collection())
        .initializeSchema(true)
        .build();
  }

  @Bean
  @Lazy
  public DocumentIndex documentIndex(VectorStore vectorStore) {
    return new DocumentIndex(vectorStore);
  }

  @Bean
  @Lazy
  public CidScopedVectorSearch cidScopedVectorSearch(
      VectorStore vectorStore, AuditService audit, RagProperties properties) {
    return new CidScopedVectorSearch(
        vectorStore, audit, properties.retrieval().denseCandidates());
  }

  // --- reranking ------------------------------------------------------------

  @Bean(destroyMethod = "close")
  @Lazy
  @ConditionalOnProperty(name = "rag.rerank.provider", havingValue = "onnx", matchIfMissing = true)
  public OnnxCrossEncoder crossEncoder(RagProperties properties) throws java.io.IOException {
    RagProperties.Rerank.Onnx onnx = properties.rerank().onnx();
    return OnnxCrossEncoder.load(
        Path.of(onnx.modelPath()), Path.of(onnx.tokenizerPath()), onnx.maxSequenceLength(), 4);
  }

  @Bean
  @Lazy
  @ConditionalOnProperty(name = "rag.rerank.provider", havingValue = "onnx", matchIfMissing = true)
  public Reranker onnxReranker(OnnxCrossEncoder crossEncoder, RagProperties properties) {
    RagProperties.Rerank.Onnx onnx = properties.rerank().onnx();
    return new OnnxReranker(crossEncoder, "ms-marco-MiniLM-L-6-v2", onnx.batchSize());
  }

  @Bean
  @Lazy
  @ConditionalOnProperty(name = "rag.rerank.provider", havingValue = "none")
  public Reranker denseOnlyReranker() {
    return new DenseOnlyReranker();
  }

  // --- ingestion / retrieval ------------------------------------------------

  @Bean(destroyMethod = "close")
  @Lazy
  public TokenCounter tokenCounter(RagProperties properties) throws java.io.IOException {
    // The embedding model's tokenizer, so chunk sizes are measured in the unit
    // the embedding context window uses.
    return new HfTokenCounter(Path.of(properties.embedding().transformers().tokenizerPath()));
  }

  /**
   * Tika's text formatter: immutable, so one instance is shared by every ingest.
   *
   * <p>Line deletions default to zero and exist as knobs only: stripping running
   * heads here would fight the cleaner, which sees the text after this point.
   */
  @Bean
  public ExtractedTextFormatter extractedTextFormatter(RagProperties properties) {
    RagProperties.Ingestion.Formatter settings = properties.ingestion().formatter();
    if (settings.deleteTopLines() < 0 || settings.deleteBottomLines() < 0) {
      throw new IllegalArgumentException(
          "rag.ingestion.formatter line deletions must not be negative");
    }
    return ExtractedTextFormatter.builder()
        .withLeftAlignment(settings.leftAlignment())
        .withNumberOfTopTextLinesToDelete(settings.deleteTopLines())
        .withNumberOfBottomTextLinesToDelete(settings.deleteBottomLines())
        .build();
  }

  @Bean
  public TikaTextExtractor tikaTextExtractor(
      RagProperties properties, ExtractedTextFormatter extractedTextFormatter) {
    return new TikaTextExtractor(properties.ingestion().maxFileBytes(), extractedTextFormatter);
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
  public RelevanceGate relevanceGate(RagProperties properties) {
    RagProperties.Rerank.Onnx onnx = properties.rerank().onnx();
    return new RelevanceGate(onnx.minRelevance(), onnx.minChunkRelevance());
  }

  @Bean
  @Lazy
  public ContextAssembler contextAssembler(
      TokenCounter tokenCounter, Reranker reranker, RagProperties properties) {
    // The scoring scale travels from whichever reranker is active, so display
    // relevance is correct for a logit or a normalised provider.
    return new ContextAssembler(
        tokenCounter, properties.retrieval().contextTokenBudget(), reranker.scale());
  }

  @Bean
  @Lazy
  public RetrievalService retrievalService(
      CidScopedVectorSearch search,
      Reranker reranker,
      InputGuard inputGuard,
      RelevanceGate relevanceGate,
      ContextAssembler contextAssembler,
      AuditService audit,
      RagProperties properties) {
    return new RetrievalService(
        search, reranker, inputGuard, relevanceGate, contextAssembler, audit, properties);
  }

  // --- chat -----------------------------------------------------------------

  /**
   * The provider-neutral chat seam.
   *
   * <p>Which model this talks to is decided by {@code spring.ai.model.chat} and
   * the starter on the classpath; no application code names a provider. It is
   * lazy so a deployment without an API key still starts, and the failure is
   * reported on the first question instead of at boot.
   */
  @Bean
  @Lazy
  public ChatClient chatClient(ChatClient.Builder builder) {
    return builder.build();
  }

  @Bean
  @Lazy
  public ChatService chatService(
      ObjectProvider<RetrievalService> retrievalService,
      ObjectProvider<ChatClient> chatClient,
      CidService cids) {
    return new ChatService(retrievalService, chatClient, cids);
  }
}
