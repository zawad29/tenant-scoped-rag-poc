package com.example.ragpoc.retrieval.search;

import com.example.ragpoc.audit.entity.AuditEvent;
import com.example.ragpoc.audit.service.AuditService;
import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.retrieval.CidMismatchException;
import com.example.ragpoc.retrieval.model.SearchHit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * The dense-retrieval entry point: the one place a cid-scoped search is built.
 *
 * <p>Spring AI's {@link VectorStore} takes a caller-supplied
 * {@link SearchRequest}, so the "the store builds the filter, callers cannot
 * omit it" property is enforced here instead. Two guarantees are kept:
 *
 * <ol>
 *   <li>The cid pre-filter is constructed from the {@link CidContext} argument,
 *       never from a caller-supplied expression.
 *   <li>Every returned document's recorded cid is compared against the caller's
 *       and a mismatch aborts the request and writes a security event. The store
 *       already filters; this is the independent second opinion.
 * </ol>
 */
@Slf4j
public final class CidScopedVectorSearch {

  private final VectorStore vectorStore;
  private final AuditService audit;
  private final int denseCandidates;

  public CidScopedVectorSearch(VectorStore vectorStore, AuditService audit, int denseCandidates) {
    this.vectorStore = vectorStore;
    this.audit = audit;
    this.denseCandidates = denseCandidates;
  }

  /** Embeds the query, searches within the caller's cid, and verifies the result. */
  public List<SearchHit> search(CidContext cid, String question) {
    int queryChars = question == null ? 0 : question.length();
    log.info(
        "retrieve step=search-start cid={} topK={} queryChars={}",
        cid.cidId(), denseCandidates, queryChars);
    if (log.isDebugEnabled()) {
      log.debug("retrieve step=search query=\"{}\"", question);
    }

    SearchRequest request =
        SearchRequest.builder()
            .query(question)
            .topK(denseCandidates)
            .filterExpression(cidFilter(cid))
            .build();

    List<Document> documents = vectorStore.similaritySearch(request);
    log.info(
        "retrieve step=search-done cid={} candidates={}", cid.cidId(), documents.size());
    verifyCid(cid, documents);

    List<SearchHit> hits = new ArrayList<>(documents.size());
    for (Document document : documents) {
      hits.add(toHit(document));
    }
    return hits;
  }

  private static String cidFilter(CidContext cid) {
    return ChunkMetadata.CID_ID + " == '" + cid.cidId() + "'";
  }

  /** Asserts that nothing from another cid came back. Deliberately fatal. */
  private void verifyCid(CidContext cid, List<Document> documents) {
    for (Document document : documents) {
      String recorded = String.valueOf(document.getMetadata().get(ChunkMetadata.CID_ID));
      if (!cid.cidId().toString().equals(recorded)) {
        log.warn(
            "retrieve step=cid-verification FAILED cid={} chunk={} recordedCid={}",
            cid.cidId(), document.getId(), recorded);
        audit.record(
            cid.cidId(),
            cid.userId(),
            AuditEvent.Type.SECURITY_CID_MISMATCH,
            "chunk " + document.getId() + " retrieved for cid " + cid.cidId() + " belongs to cid " + recorded);
        throw new CidMismatchException(
            "Retrieved a chunk belonging to another cid; aborting the request");
      }
    }
  }

  private static SearchHit toHit(Document document) {
    var metadata = document.getMetadata();
    String sectionTitle =
        metadata.get(ChunkMetadata.SECTION_TITLE) == null
            ? null
            : metadata.get(ChunkMetadata.SECTION_TITLE).toString();

    // Tika has no page boundaries, so every chunk reports page 1.
    var hit =
        new SearchHit(
            UUID.fromString(document.getId()),
            UUID.fromString(String.valueOf(metadata.get(ChunkMetadata.CID_ID))),
            UUID.fromString(String.valueOf(metadata.get(ChunkMetadata.DOCUMENT_ID))),
            ((Number) metadata.get(ChunkMetadata.VERSION)).intValue(),
            1,
            1,
            sectionTitle,
            String.valueOf(metadata.get(ChunkMetadata.DOC_TITLE)),
            document.getText(),
            document.getScore() == null ? 0.0 : document.getScore());
    log.debug(
        "retrieve candidate chunk={} document=\"{}\" denseScore={} chars={}",
        hit.chunkId(), hit.docTitle(), hit.score(), hit.text().length());
    return hit;
  }
}
