package com.example.ragpoc.config;

import com.example.ragpoc.adapter.embed.OnnxEmbeddingAdapter;
import com.example.ragpoc.adapter.onnx.HfTokenCounter;
import com.example.ragpoc.adapter.onnx.OnnxTextModel;
import com.example.ragpoc.adapter.onnx.PoolingMode;
import com.example.ragpoc.adapter.rerank.OnnxCrossEncoderRerankerAdapter;
import com.example.ragpoc.adapter.vector.pgvector.PgVectorIndexAdapter;
import com.example.ragpoc.ingest.DocumentChunker;
import com.example.ragpoc.ingest.PdfPageExtractor;
import com.example.ragpoc.ingest.TextCleaner;
import com.example.ragpoc.ingest.TokenCounter;
import com.example.ragpoc.port.EmbeddingPort;
import com.example.ragpoc.port.RerankerPort;
import com.example.ragpoc.port.VectorIndexPort;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the configuration to concrete adapters.
 *
 * <p>This is the only place in the application that knows which implementation
 * is behind a port. Everything else depends on the interfaces, which is what
 * makes the provider-agnostic requirement real rather than aspirational, and
 * what the architecture tests enforce.
 *
 * <p>The ONNX models are loaded once at startup and closed on shutdown. Loading
 * them lazily on first use would move a 400 MB read and a multi-second session
 * build into the first user's request, and would turn a missing model file into
 * a runtime failure instead of a failed deployment.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class AdapterConfiguration {

  // --- models ---------------------------------------------------------------

  @Bean(destroyMethod = "close")
  public OnnxTextModel embeddingModel(RagProperties properties) throws IOException {
    RagProperties.Embedding embedding = properties.embedding();
    return OnnxTextModel.load(
        Path.of(embedding.modelPath()),
        Path.of(embedding.tokenizerPath()),
        embedding.maxSequenceLength(),
        // Modest thread count: the box also runs Postgres and the app, and
        // oversubscribing here slows the whole system rather than this call.
        4);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      name = "rag.rerank.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public OnnxTextModel rerankerModel(RagProperties properties) throws IOException {
    RagProperties.Rerank rerank = properties.rerank();
    return OnnxTextModel.load(
        Path.of(rerank.modelPath()), Path.of(rerank.tokenizerPath()), rerank.maxSequenceLength(), 4);
  }

  @Bean(destroyMethod = "close")
  public HfTokenCounter tokenCounter(RagProperties properties) throws IOException {
    // The embedding model's tokenizer, so chunk sizes are measured in the unit
    // the embedding context window actually uses (decision D7).
    return new HfTokenCounter(Path.of(properties.embedding().tokenizerPath()));
  }

  // --- ports ----------------------------------------------------------------

  @Bean
  public EmbeddingPort embeddingPort(
      OnnxTextModel embeddingModel, RagProperties properties) {
    RagProperties.Embedding embedding = properties.embedding();
    return new OnnxEmbeddingAdapter(
        embeddingModel,
        embedding.modelId(),
        parsePoolingMode(embedding.pooling()),
        embedding.normalize(),
        embedding.dimension(),
        embedding.batchSize());
  }

  @Bean
  @ConditionalOnProperty(
      name = "rag.rerank.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public RerankerPort rerankerPort(OnnxTextModel rerankerModel, RagProperties properties) {
    return new OnnxCrossEncoderRerankerAdapter(
        rerankerModel, properties.rerank().modelId(), properties.rerank().batchSize());
  }

  /**
   * The pgvector adapter, selected by configuration.
   *
   * <p>{@code matchIfMissing = true} so the default profile works without
   * setting anything; the alternative store is selected by naming it.
   */
  @Bean
  @ConditionalOnProperty(
      name = "rag.vector.provider",
      havingValue = "pgvector",
      matchIfMissing = true)
  public VectorIndexPort pgVectorIndexPort(
      NamedParameterJdbcTemplate namedJdbc,
      JdbcClient jdbc,
      TransactionTemplate transactions,
      RagProperties properties) {
    return new PgVectorIndexAdapter(
        namedJdbc,
        jdbc,
        transactions,
        properties.embedding().dimension(),
        // A fixed, generous search breadth; recall under the tenant filter is
        // verified by RecallsUnderFilterIT rather than assumed.
        100);
  }

  // --- ingestion pipeline ---------------------------------------------------

  @Bean
  public PdfPageExtractor pdfPageExtractor(RagProperties properties) {
    return new PdfPageExtractor(
        properties.ingestion().maxFileBytes(), properties.ingestion().maxPages());
  }

  @Bean
  public com.example.ragpoc.port.FileStoragePort fileStoragePort(RagProperties properties) {
    // Local filesystem for the PoC; the port is what allows an S3-compatible
    // implementation later without touching callers.
    return new com.example.ragpoc.adapter.storage.fs.FsFileStorageAdapter(
        java.nio.file.Path.of(properties.storage().localRoot()));
  }

  @Bean
  public TextCleaner textCleaner() {
    return new TextCleaner();
  }

  @Bean
  public DocumentChunker documentChunker(TokenCounter tokenCounter, RagProperties properties) {
    RagProperties.Chunking chunking = properties.chunking();
    return new DocumentChunker(
        tokenCounter,
        chunking.targetTokens(),
        chunking.maxTokens(),
        chunking.minTokens(),
        chunking.overlapPercent());
  }

  // --- helpers --------------------------------------------------------------

  private static PoolingMode parsePoolingMode(String configured) {
    try {
      return PoolingMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalStateException(
          "Unrecognised rag.embedding.pooling value '"
              + configured
              + "'. Use CLS for bge models and MEAN for sentence-transformers models. "
              + "The wrong choice degrades retrieval silently, so it is not defaulted.",
          e);
    }
  }
}
