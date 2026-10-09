package com.example.ragpoc.document;

/** Lifecycle of a document, mirrored by a check constraint on the table. */
public enum DocumentStatus {
  /** Accepted; an ingestion job has been queued. */
  UPLOADED,
  /** An ingestion job is running. */
  PROCESSING,
  /** Indexed and answerable. */
  ACTIVE,
  /** Ingestion failed; the error is on the job. */
  FAILED,
  /** Marked for removal; chunks and files are being cleaned up. */
  DELETING
}
