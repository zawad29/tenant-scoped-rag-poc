package com.example.ragpoc.retrieval.service;

import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.config.RagProperties;
import com.example.ragpoc.retrieval.model.ChunkRecord;
import com.example.ragpoc.retrieval.model.SearchHit;
import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.Filter;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.WriteOrderingType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;

/**
 * The only way anything in this application touches document vectors.
 *
 * <p>Every method takes a {@link CidContext} and the cid filter is built here,
 * from that argument, never accepted from the caller. The filter is applied as a
 * pre-filter, so other companies' points never take part in the search.
 *
 * <p>Chunk text and metadata live in the point payload. Each point also carries
 * an {@code active} flag: only the active version of a document is searchable, so
 * a replacement can write the new version first and switch over without the
 * document ever becoming unavailable. Qdrant has no transactions, so the switch
 * is deliberately ordered to prefer a brief both-active window over a gap.
 */
@Slf4j
public class QdrantVectorStore {

  private static final String CID = "cid_id";
  private static final String DOCUMENT = "document_id";
  private static final String VERSION = "version";
  private static final String ACTIVE = "active";

  private final QdrantClient client;
  private final String collection;
  private final int dimension;
  private final Duration timeout;

  public QdrantVectorStore(RagProperties.Qdrant settings, int dimension) {
    QdrantGrpcClient.Builder builder =
        QdrantGrpcClient.newBuilder(settings.host(), settings.port(), settings.useTls());
    if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
      builder.withApiKey(settings.apiKey());
    }
    this.client = new QdrantClient(builder.build());
    this.collection = settings.collection();
    this.dimension = dimension;
    this.timeout = Duration.ofMillis(settings.timeoutMs());
  }

  @PostConstruct
  void init() {
    if (!await(client.collectionExistsAsync(collection))) {
      await(
          client.createCollectionAsync(
              collection,
              VectorParams.newBuilder()
                  .setSize(dimension)
                  .setDistance(Distance.Cosine)
                  .build()));
      log.info("Created Qdrant collection '{}' (dimension {})", collection, dimension);
    }
    indexKeyword(CID);
    indexKeyword(DOCUMENT);
    indexInteger(VERSION);
    indexBool(ACTIVE);
  }

  private void indexKeyword(String field) {
    createIndex(field, PayloadSchemaType.Keyword);
  }

  private void indexInteger(String field) {
    createIndex(field, PayloadSchemaType.Integer);
  }

  private void indexBool(String field) {
    createIndex(field, PayloadSchemaType.Bool);
  }

  private void createIndex(String field, PayloadSchemaType type) {
    try {
      await(
          client.createPayloadIndexAsync(
              collection, field, type, PayloadIndexParams.getDefaultInstance(), true,
              WriteOrderingType.Strong, timeout));
    } catch (RuntimeException e) {
      // An index that already exists is fine; anything else surfaces on the
      // first write or search rather than being swallowed silently.
      log.debug("Payload index on '{}' was not created: {}", field, e.getMessage());
    }
  }

  @PreDestroy
  void close() {
    client.close();
  }

  // --- writes ---------------------------------------------------------------

  /** Inserts or replaces chunks as inactive; {@link #setActive} promotes them. */
  public void upsert(CidContext cid, List<ChunkRecord> chunks) {
    if (chunks.isEmpty()) {
      return;
    }
    List<PointStruct> points = chunks.stream().map(chunk -> toPoint(cid, chunk)).toList();
    await(client.upsertAsync(collection, points));
  }

  private PointStruct toPoint(CidContext cid, ChunkRecord chunk) {
    Map<String, Value> payload = new LinkedHashMap<>();
    payload.put(CID, value(cid.cidId().toString()));
    payload.put(DOCUMENT, value(chunk.documentId().toString()));
    payload.put(VERSION, value((long) chunk.version()));
    payload.put("chunk_index", value((long) chunk.chunkIndex()));
    payload.put("page_start", value((long) chunk.pageStart()));
    payload.put("page_end", value((long) chunk.pageEnd()));
    if (chunk.sectionTitle() != null) {
      payload.put("section_title", value(chunk.sectionTitle()));
    }
    payload.put("doc_title", value(chunk.docTitle()));
    payload.put("text", value(chunk.text()));
    payload.put("embedding_model", value(chunk.embeddingModel()));
    // Written inactive; promotion is a separate, deliberate step.
    payload.put(ACTIVE, value(false));

    return PointStruct.newBuilder()
        .setId(id(chunk.chunkId()))
        .setVectors(vectors(chunk.embedding()))
        .putAllPayload(payload)
        .build();
  }

  /** Promotes or demotes one document version's points. */
  public void setActive(CidContext cid, UUID documentId, int version, boolean active) {
    await(
        client.setPayloadAsync(
            collection,
            Map.of(ACTIVE, value(active)),
            versionFilter(cid, documentId, version),
            true,
            WriteOrderingType.Strong,
            timeout));
  }

  public void deleteByDocument(CidContext cid, UUID documentId) {
    await(client.deleteAsync(collection, documentFilter(cid, documentId)));
  }

  public void deleteByDocumentVersion(CidContext cid, UUID documentId, int version) {
    await(client.deleteAsync(collection, versionFilter(cid, documentId, version)));
  }

  public long countByDocument(CidContext cid, UUID documentId) {
    return await(client.countAsync(collection, documentFilter(cid, documentId), true));
  }

  // --- search ---------------------------------------------------------------

  public List<SearchHit> denseSearch(
      CidContext cid, float[] queryVector, int limit, Set<UUID> documentIds) {

    SearchPoints.Builder request =
        SearchPoints.newBuilder()
            .setCollectionName(collection)
            .addAllVector(toFloatList(queryVector))
            .setFilter(searchFilter(cid, documentIds))
            .setLimit(limit)
            .setWithPayload(enable(true));

    List<ScoredPoint> points = await(client.searchAsync(request.build()));
    List<SearchHit> hits = new ArrayList<>(points.size());
    for (ScoredPoint point : points) {
      hits.add(toHit(point));
    }
    return hits;
  }

  private static SearchHit toHit(ScoredPoint point) {
    Map<String, Value> payload = point.getPayloadMap();
    return new SearchHit(
        UUID.fromString(point.getId().getUuid()),
        // From the row, not the request: the retrieval service compares this
        // against the caller's cid and aborts on any mismatch.
        UUID.fromString(required(payload, CID)),
        UUID.fromString(required(payload, DOCUMENT)),
        (int) payload.get(VERSION).getIntegerValue(),
        (int) payload.get("page_start").getIntegerValue(),
        (int) payload.get("page_end").getIntegerValue(),
        payload.containsKey("section_title") ? payload.get("section_title").getStringValue() : null,
        required(payload, "doc_title"),
        required(payload, "text"),
        point.getScore());
  }

  // --- filters --------------------------------------------------------------

  private static Filter searchFilter(CidContext cid, Set<UUID> documentIds) {
    Filter.Builder filter =
        Filter.newBuilder()
            .addMust(matchKeyword(CID, cid.cidId().toString()))
            .addMust(match(ACTIVE, true));
    if (documentIds != null && !documentIds.isEmpty()) {
      filter.addMust(
          matchKeywords(DOCUMENT, documentIds.stream().map(UUID::toString).toList()));
    }
    return filter.build();
  }

  private static Filter documentFilter(CidContext cid, UUID documentId) {
    return Filter.newBuilder()
        .addMust(matchKeyword(CID, cid.cidId().toString()))
        .addMust(matchKeyword(DOCUMENT, documentId.toString()))
        .build();
  }

  private static Filter versionFilter(CidContext cid, UUID documentId, int version) {
    return Filter.newBuilder()
        .addMust(matchKeyword(CID, cid.cidId().toString()))
        .addMust(matchKeyword(DOCUMENT, documentId.toString()))
        .addMust(match(VERSION, (long) version))
        .build();
  }

  // --- helpers --------------------------------------------------------------

  private static String required(Map<String, Value> payload, String key) {
    Value value = payload.get(key);
    if (value == null) {
      throw new IllegalStateException("Qdrant point is missing payload field '" + key + "'");
    }
    return value.getStringValue();
  }

  private static List<Float> toFloatList(float[] vector) {
    List<Float> list = new ArrayList<>(vector.length);
    for (float v : vector) {
      list.add(v);
    }
    return list;
  }

  private static <T> T await(ListenableFuture<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while talking to Qdrant", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException("Qdrant call failed: " + cause.getMessage(), cause);
    }
  }
}
