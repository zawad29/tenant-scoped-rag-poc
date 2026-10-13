package com.example.ragpoc.retrieval.search;

import com.example.ragpoc.cid.CidContext;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * Writes and removes chunks through Spring AI's {@link VectorStore}.
 *
 * <p>Every method takes a {@link CidContext} and builds the cid filter here, from
 * that argument, so a caller cannot omit the scope. Embedding happens inside the
 * vector store, which is where the {@code ContextualEmbeddingModel} adds the
 * document-context prefix.
 */
@Slf4j
public final class DocumentIndex {
  private final VectorStore vectorStore;

  public DocumentIndex(VectorStore vectorStore) {
    this.vectorStore = vectorStore;
  }

  public void add(List<Document> documents) {
    if (documents.isEmpty()) {
      log.debug("index add skipped: no documents");
      return;
    }
    log.info("index step=add documents={}", documents.size());
    vectorStore.add(documents);
  }

  public void deleteByDocument(CidContext cid, UUID documentId) {
    log.info("index step=delete-document cid={} document={}", cid.cidId(), documentId);
    FilterExpressionBuilder builder = new FilterExpressionBuilder();
    vectorStore.delete(
        builder
            .and(
                builder.eq(ChunkMetadata.CID_ID, cid.cidId().toString()),
                builder.eq(ChunkMetadata.DOCUMENT_ID, documentId.toString()))
            .build());
  }

  public void deleteByDocumentVersion(CidContext cid, UUID documentId, int version) {
    log.info(
        "index step=delete-version cid={} document={} version={}", cid.cidId(), documentId, version);
    FilterExpressionBuilder builder = new FilterExpressionBuilder();
    vectorStore.delete(
        builder
            .and(
                builder.eq(ChunkMetadata.CID_ID, cid.cidId().toString()),
                builder.and(
                    builder.eq(ChunkMetadata.DOCUMENT_ID, documentId.toString()),
                    builder.eq(ChunkMetadata.VERSION, version)))
            .build());
  }
}
