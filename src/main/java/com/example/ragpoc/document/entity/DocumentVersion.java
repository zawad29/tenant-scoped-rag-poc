package com.example.ragpoc.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One ingested version of a document.
 *
 * <p>The embedding model and its dimension are recorded per version: the only
 * safe way to change embedding model is to rebuild into a new collection and
 * switch over, and this makes that migration auditable.
 */
@Entity
@Table(name = "document_version")
public class DocumentVersion {

  @Id private UUID id;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(nullable = false)
  private int version;

  @Column(name = "storage_key", nullable = false)
  private String storageKey;

  @Column(name = "content_hash", nullable = false)
  private String contentHash;

  @Column(name = "embedding_model")
  private String embeddingModel;

  @Column(name = "embedding_dim")
  private Integer embeddingDim;

  @Column(name = "chunk_count", nullable = false)
  private int chunkCount;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected DocumentVersion() {}

  public DocumentVersion(
      UUID id, UUID documentId, int version, String storageKey, String contentHash) {
    this.id = id;
    this.documentId = documentId;
    this.version = version;
    this.storageKey = storageKey;
    this.contentHash = contentHash;
    this.chunkCount = 0;
  }

  public void recordIngestionResult(String embeddingModel, int embeddingDim, int chunkCount) {
    this.embeddingModel = embeddingModel;
    this.embeddingDim = embeddingDim;
    this.chunkCount = chunkCount;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public int getVersion() {
    return version;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public String getContentHash() {
    return contentHash;
  }

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public Integer getEmbeddingDim() {
    return embeddingDim;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
