package com.example.ragpoc.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A document, as stored.
 *
 * <p>{@code currentVersion} is the only version whose chunks are answerable; 0
 * before the first successful ingestion. A first ingestion moves the status, but
 * a replacement leaves an already-active document ACTIVE so updating it never
 * causes an outage.
 */
@Entity
@Table(name = "document")
public class Document {

  @Id private UUID id;

  @Column(name = "cid_id", nullable = false)
  private UUID cidId;

  @Column(nullable = false)
  private String title;

  @Column(name = "original_filename", nullable = false)
  private String originalFilename;

  @Column(name = "content_hash")
  private String contentHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DocumentStatus status;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @Column(name = "page_count")
  private Integer pageCount;

  @Column(name = "uploaded_by")
  private UUID uploadedBy;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Document() {}

  public Document(
      UUID id,
      UUID cidId,
      String title,
      String originalFilename,
      String contentHash,
      UUID uploadedBy) {
    this.id = id;
    this.cidId = cidId;
    this.title = title;
    this.originalFilename = originalFilename;
    this.contentHash = contentHash;
    this.status = DocumentStatus.UPLOADED;
    this.currentVersion = 0;
    this.uploadedBy = uploadedBy;
    this.updatedAt = Instant.now();
  }

  /** Marks a first-time ingestion as in progress; a replacement stays ACTIVE. */
  public void markProcessing() {
    if (currentVersion == 0) {
      this.status = DocumentStatus.PROCESSING;
      this.updatedAt = Instant.now();
    }
  }

  /** Marks a failed first-time ingestion; a failed replacement keeps its version. */
  public void markFailed() {
    if (currentVersion == 0) {
      this.status = DocumentStatus.FAILED;
      this.updatedAt = Instant.now();
    }
  }

  public void markDeleting() {
    this.status = DocumentStatus.DELETING;
    this.updatedAt = Instant.now();
  }

  /**
   * Makes a version answerable. The switch is a single update: chunks are already
   * written, and queries join on the active version, so no chunk row is rewritten.
   *
   * <p>{@code pageCount} is left untouched: Tika gives no page boundaries and no
   * page count, so the column stays null rather than holding a guess. The admin
   * list renders that as a dash.
   */
  public void activate(int version, String contentHash) {
    this.status = DocumentStatus.ACTIVE;
    this.currentVersion = version;
    this.contentHash = contentHash;
    this.updatedAt = Instant.now();
  }

  public boolean active() {
    return status == DocumentStatus.ACTIVE;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCidId() {
    return cidId;
  }

  public String getTitle() {
    return title;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }

  public String getContentHash() {
    return contentHash;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public int getCurrentVersion() {
    return currentVersion;
  }

  public Integer getPageCount() {
    return pageCount;
  }

  public UUID getUploadedBy() {
    return uploadedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
