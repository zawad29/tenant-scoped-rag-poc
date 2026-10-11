package com.example.ragpoc.document.entity;

/** Lifecycle of a document, mirrored by a check constraint on the table. */
public enum DocumentStatus {
  /** Accepted; ingestion is in progress on the upload request. */
  UPLOADED,
  /** Ingestion is running. */
  PROCESSING,
  /** Indexed and answerable. */
  ACTIVE,
  /** Ingestion failed; the error was reported to the uploader. */
  FAILED,
  /** Marked for removal; chunks and files are being cleaned up. */
  DELETING
}
