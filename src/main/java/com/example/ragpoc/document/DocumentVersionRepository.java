package com.example.ragpoc.document;

import com.example.ragpoc.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Version persistence. Tenant scoped like every other repository. */
@Repository
public class DocumentVersionRepository {

  private static final String COLUMNS =
      "id, document_id, tenant_id, version, storage_key, content_hash, embedding_model, "
          + "embedding_dim, chunk_count, created_at";

  private final JdbcClient jdbc;

  public DocumentVersionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(DocumentVersion version) {
    jdbc.sql(
            """
            INSERT INTO document_version (id, document_id, tenant_id, version, storage_key,
                                          content_hash, embedding_model, embedding_dim, chunk_count)
            VALUES (:id, :documentId, :tenantId, :version, :storageKey, :contentHash,
                    :embeddingModel, :embeddingDim, :chunkCount)
            """)
        .param("id", version.id())
        .param("documentId", version.documentId())
        .param("tenantId", version.tenantId())
        .param("version", version.version())
        .param("storageKey", version.storageKey())
        .param("contentHash", version.contentHash())
        .param("embeddingModel", version.embeddingModel())
        .param("embeddingDim", version.embeddingDim())
        .param("chunkCount", version.chunkCount())
        .update();
  }

  public Optional<DocumentVersion> find(TenantContext tenant, UUID documentId, int version) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM document_version WHERE tenant_id = :tenantId AND document_id = :documentId"
                + " AND version = :version")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .param("version", version)
        .query(DocumentVersionRepository::map)
        .optional();
  }

  /** Next version number for a document; 1 for a document with no versions yet. */
  public int nextVersion(TenantContext tenant, UUID documentId) {
    return jdbc.sql(
            "SELECT coalesce(max(version), 0) + 1 FROM document_version "
                + "WHERE tenant_id = :tenantId AND document_id = :documentId")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .query(Integer.class)
        .single();
  }

  /**
   * Records what the ingestion produced.
   *
   * <p>The model and dimension are stored per version: they are the only record
   * that a future re-index was necessary, and mixing models in one index is
   * never valid.
   */
  public void recordIngestionResult(
      TenantContext tenant,
      UUID documentId,
      int version,
      String embeddingModel,
      int embeddingDim,
      int chunkCount) {
    jdbc.sql(
            """
            UPDATE document_version
               SET embedding_model = :embeddingModel,
                   embedding_dim = :embeddingDim,
                   chunk_count = :chunkCount
             WHERE tenant_id = :tenantId AND document_id = :documentId AND version = :version
            """)
        .param("embeddingModel", embeddingModel)
        .param("embeddingDim", embeddingDim)
        .param("chunkCount", chunkCount)
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .param("version", version)
        .update();
  }

  /** Versions other than the one given, used to clean up after a replacement. */
  public List<Integer> versionsOtherThan(TenantContext tenant, UUID documentId, int keepVersion) {    return jdbc.sql(
            "SELECT version FROM document_version "
                + "WHERE tenant_id = :tenantId AND document_id = :documentId AND version <> :keep")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .param("keep", keepVersion)
        .query(Integer.class)
        .list();
  }

  /** Every version of a document, newest first. */
  public List<DocumentVersion> listByDocument(TenantContext tenant, UUID documentId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM document_version WHERE tenant_id = :tenantId AND document_id = :documentId"
                + " ORDER BY version DESC")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .query(DocumentVersionRepository::map)
        .list();
  }

  public void delete(TenantContext tenant, UUID documentId, int version) {
    jdbc.sql(
            "DELETE FROM document_version "
                + "WHERE tenant_id = :tenantId AND document_id = :documentId AND version = :version")
        .param("tenantId", tenant.tenantId())
        .param("documentId", documentId)
        .param("version", version)
        .update();
  }

  private static DocumentVersion map(ResultSet rs, int rowNum) throws SQLException {
    return new DocumentVersion(
        rs.getObject("id", UUID.class),
        rs.getObject("document_id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getInt("version"),
        rs.getString("storage_key"),
        rs.getString("content_hash"),
        rs.getString("embedding_model"),
        rs.getObject("embedding_dim", Integer.class),
        rs.getInt("chunk_count"),
        rs.getTimestamp("created_at").toInstant());
  }
}
