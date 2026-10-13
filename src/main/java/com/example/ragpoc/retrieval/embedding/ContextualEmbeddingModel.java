package com.example.ragpoc.retrieval.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Decorates any {@link EmbeddingModel} with the document-context prefix.
 *
 * <p>A chunk such as "eighteen days per calendar year" embeds poorly on its own;
 * prefixing the document and section titles gives the embedding model the
 * context it needs. The prefix is only ever used for embedding — the
 * {@link Document}'s stored text stays clean, so citations quote the document,
 * not our scaffolding.
 *
 * <p>The delegate is the provider seam: today a local ONNX
 * {@code TransformersEmbeddingModel}, later a hosted embedding service. Swapping
 * it changes nothing here, which is why this is a decorator rather than a
 * subclass of a concrete model.
 */
public final class ContextualEmbeddingModel implements EmbeddingModel, AutoCloseable {

  static final String DOC_TITLE = "doc_title";
  static final String SECTION_TITLE = "section_title";

  private final EmbeddingModel delegate;

  public ContextualEmbeddingModel(EmbeddingModel delegate) {
    this.delegate = delegate;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    return delegate.call(request);
  }

  @Override
  public float[] embed(Document document) {
    return delegate.embed(contextualise(document));
  }

  /**
   * Used by Spring AI's default batch document embedding, which is the path the
   * vector store takes when it writes chunks.
   */
  @Override
  public String getEmbeddingContent(Document document) {
    return contextualise(document);
  }

  @Override
  public int dimensions() {
    return delegate.dimensions();
  }

  /** Releases the delegate's native resources when it owns any. */
  @Override
  public void close() throws Exception {
    if (delegate instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }

  private static String contextualise(Document document) {
    String title = stringMetadata(document, DOC_TITLE);
    String section = stringMetadata(document, SECTION_TITLE);
    String body = document.getText() == null ? "" : document.getText();

    StringBuilder text = new StringBuilder();
    if (!title.isBlank()) {
      text.append(title);
    }
    if (!section.isBlank()) {
      if (!text.isEmpty()) {
        text.append(". ");
      }
      text.append(section);
    }
    if (!text.isEmpty()) {
      text.append(". ");
    }
    return text.append(body).toString();
  }

  private static String stringMetadata(Document document, String key) {
    Object value = document.getMetadata().get(key);
    return value == null ? "" : value.toString();
  }
}
