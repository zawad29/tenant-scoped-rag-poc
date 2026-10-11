package com.example.ragpoc.retrieval.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.config.RagProperties;
import com.example.ragpoc.retrieval.model.ChunkRecord;
import com.example.ragpoc.retrieval.model.SearchHit;
import com.example.ragpoc.support.IntegrationTestSupport;
import com.example.ragpoc.user.entity.Role;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vector-store isolation, with synthetic vectors so it needs no ONNX models.
 *
 * <p>This is where the cid filter actually lives, so it is the place to prove
 * that one company's points never participate in another's search.
 */
class QdrantVectorStoreIT extends IntegrationTestSupport {

  private static final int DIMENSION = 4;

  private QdrantVectorStore store;

  @BeforeEach
  void setUp() {
    // A fresh collection per test, so tests cannot interfere through the store.
    store =
        new QdrantVectorStore(
            new RagProperties.Qdrant(
                QDRANT.getHost(), QDRANT.getGrpcPort(), "", "it-" + UUID.randomUUID(), false, 30_000),
            DIMENSION);
    store.init();
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  @DisplayName("search returns only the calling cid's chunks")
  void searchIsConfinedToTheCallingCid() {
    CidContext cidA = cid();
    CidContext cidB = cid();
    UUID documentA = UUID.randomUUID();
    UUID documentB = UUID.randomUUID();

    store.upsert(cidA, List.of(chunk(cidA, documentA, 1, 0, new float[] {1, 0, 0, 0}, "ALPHA-CANARY")));
    store.upsert(cidB, List.of(chunk(cidB, documentB, 1, 0, new float[] {1, 0, 0, 0}, "BETA-CANARY")));
    store.setActive(cidA, documentA, 1, true);
    store.setActive(cidB, documentB, 1, true);

    List<SearchHit> hitsForA = store.denseSearch(cidA, new float[] {1, 0, 0, 0}, 10, null);

    assertThat(hitsForA).hasSize(1);
    assertThat(hitsForA.getFirst().cidId()).isEqualTo(cidA.cidId());
    assertThat(hitsForA.getFirst().text()).isEqualTo("ALPHA-CANARY");
    assertThat(hitsForA).noneMatch(hit -> hit.text().equals("BETA-CANARY"));
  }

  @Test
  @DisplayName("an inactive version is not searchable until it is promoted")
  void onlyActiveVersionsAreSearchable() {
    CidContext cid = cid();
    UUID document = UUID.randomUUID();
    store.upsert(cid, List.of(chunk(cid, document, 1, 0, new float[] {0, 1, 0, 0}, "pending")));

    assertThat(store.denseSearch(cid, new float[] {0, 1, 0, 0}, 10, null)).isEmpty();

    store.setActive(cid, document, 1, true);
    assertThat(store.denseSearch(cid, new float[] {0, 1, 0, 0}, 10, null)).hasSize(1);

    store.setActive(cid, document, 1, false);
    assertThat(store.denseSearch(cid, new float[] {0, 1, 0, 0}, 10, null)).isEmpty();
  }

  @Test
  @DisplayName("deleting a document affects only that document and only the caller")
  void deleteIsScopedToCidAndDocument() {
    CidContext cidA = cid();
    CidContext cidB = cid();
    UUID documentA1 = UUID.randomUUID();
    UUID documentA2 = UUID.randomUUID();
    UUID documentB = UUID.randomUUID();

    store.upsert(cidA, List.of(chunk(cidA, documentA1, 1, 0, new float[] {1, 0, 0, 0}, "A1")));
    store.upsert(cidA, List.of(chunk(cidA, documentA2, 1, 0, new float[] {0, 1, 0, 0}, "A2")));
    store.upsert(cidB, List.of(chunk(cidB, documentB, 1, 0, new float[] {0, 0, 1, 0}, "B")));
    store.setActive(cidA, documentA1, 1, true);
    store.setActive(cidA, documentA2, 1, true);
    store.setActive(cidB, documentB, 1, true);

    // B deleting A's document id removes nothing.
    store.deleteByDocument(cidB, documentA1);
    assertThat(store.countByDocument(cidA, documentA1)).isEqualTo(1);

    store.deleteByDocument(cidA, documentA1);
    assertThat(store.countByDocument(cidA, documentA1)).isZero();
    assertThat(store.countByDocument(cidA, documentA2)).isEqualTo(1);
    assertThat(store.countByDocument(cidB, documentB)).isEqualTo(1);
  }

  @Test
  @DisplayName("the document filter narrows a search within the caller's own cid")
  void documentFilterNarrowsWithinTheCid() {
    CidContext cid = cid();
    UUID document1 = UUID.randomUUID();
    UUID document2 = UUID.randomUUID();

    store.upsert(cid, List.of(chunk(cid, document1, 1, 0, new float[] {1, 0, 0, 0}, "one")));
    store.upsert(cid, List.of(chunk(cid, document2, 1, 0, new float[] {1, 0, 0, 0}, "two")));
    store.setActive(cid, document1, 1, true);
    store.setActive(cid, document2, 1, true);

    List<SearchHit> hits = store.denseSearch(cid, new float[] {1, 0, 0, 0}, 10, Set.of(document1));

    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().documentId()).isEqualTo(document1);
  }

  @Test
  @DisplayName("counts are scoped to the calling cid")
  void countIsConfinedToTheCid() {
    CidContext cidA = cid();
    CidContext cidB = cid();
    UUID sharedDocumentId = UUID.randomUUID();

    store.upsert(cidA, List.of(chunk(cidA, sharedDocumentId, 1, 0, new float[] {1, 0, 0, 0}, "A")));
    store.upsert(cidB, List.of(chunk(cidB, sharedDocumentId, 1, 0, new float[] {1, 0, 0, 0}, "B")));

    assertThat(store.countByDocument(cidA, sharedDocumentId)).isEqualTo(1);
    assertThat(store.countByDocument(cidB, sharedDocumentId)).isEqualTo(1);
  }

  // --- helpers --------------------------------------------------------------

  private static CidContext cid() {
    return CidContext.of(UUID.randomUUID(), UUID.randomUUID(), Role.CID_ADMIN);
  }

  private static ChunkRecord chunk(
      CidContext cid, UUID documentId, int version, int index, float[] vector, String text) {
    return new ChunkRecord(
        com.example.ragpoc.ingestion.chunk.ChunkIds.forChunk(cid.cidId(), documentId, version, index),
        documentId,
        version,
        index,
        1,
        1,
        null,
        "Doc",
        text,
        "test-model",
        vector);
  }
}
