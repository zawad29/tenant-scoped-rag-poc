package com.example.ragpoc.port;

import com.example.ragpoc.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The only way anything in this application touches document vectors.
 *
 * <p>Two rules make this the enforcement point for tenant isolation:
 *
 * <ol>
 *   <li><b>Every method requires a {@link TenantContext}.</b> There is no
 *       overload without one, so a caller cannot search without stating which
 *       tenant it is acting for. {@code VectorIndexPortTenantContractTest}
 *       asserts this reflectively, so the property cannot be lost in a later
 *       refactor.
 *   <li><b>The adapter applies the tenant filter itself.</b> Callers pass a
 *       tenant and a query; they never build the filter. A caller therefore
 *       cannot forget it, and a filter bug has exactly one place to live.
 * </ol>
 *
 * <p>The filter is applied as a pre-filter, in the same {@code WHERE} clause as
 * the ranking, so other tenants' rows never take part in the search rather than
 * being discarded afterwards.
 */
public interface VectorIndexPort {

  /**
   * Inserts or replaces chunks.
   *
   * <p>The tenant is taken from the argument, never from the payload: a
   * {@link ChunkRecord} carries no tenant field, so a mismatch cannot be
   * expressed. Implementations must be idempotent, because chunk identifiers are
   * deterministic and ingestion retries.
   */
  void upsert(TenantContext tenant, List<ChunkRecord> chunks);

  /**
   * Removes every chunk of a document for the calling tenant.
   *
   * @return the number of chunks removed, so deletion can be verified rather
   *     than assumed
   */
  int deleteByDocument(TenantContext tenant, UUID documentId);

  /**
   * Removes one version's chunks, leaving other versions alone.
   *
   * <p>Needed for replacements, and the order matters: the new version's chunks
   * are written, then {@code document.current_version} moves, and only then are
   * the superseded chunks removed. Deleting by document first would open a
   * window in which an ACTIVE document has no chunks at all, so a replacement
   * would silently answer nothing until it finished — the "lose answers during
   * an update" failure the specification rules out.
   *
   * @return the number of chunks removed
   */
  int deleteByDocumentVersion(TenantContext tenant, UUID documentId, int version);

  /** Vector similarity search over the calling tenant's chunks. */
  List<SearchHit> denseSearch(
      TenantContext tenant, float[] queryVector, int limit, SearchFilter filter);

  /** Keyword (full-text) search over the calling tenant's chunks. */
  List<SearchHit> keywordSearch(
      TenantContext tenant, String queryText, int limit, SearchFilter filter);

  /** Number of chunks stored for a document, used to verify deletions. */
  int countByDocument(TenantContext tenant, UUID documentId);

  /** Restricts a search to a subset of documents, within the calling tenant. */
  record SearchFilter(Set<UUID> documentIds) {

    public SearchFilter {
      documentIds = documentIds == null ? Set.of() : Set.copyOf(documentIds);
    }

    /** No additional restriction beyond the mandatory tenant filter. */
    public static SearchFilter none() {
      return new SearchFilter(Set.of());
    }

    public static SearchFilter ofDocument(UUID documentId) {
      return new SearchFilter(Set.of(documentId));
    }

    public boolean isEmpty() {
      return documentIds.isEmpty();
    }
  }
}
