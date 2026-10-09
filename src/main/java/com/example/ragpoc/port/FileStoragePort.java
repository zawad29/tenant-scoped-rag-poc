package com.example.ragpoc.port;

import com.example.ragpoc.tenant.TenantContext;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stores original PDF files.
 *
 * <p>Every method takes a {@link TenantContext}, including {@link #pathOf}, and
 * the implementation verifies that the tenant in the key matches the caller.
 * That means a storage key that leaks — through a log line, an error message, a
 * stale browser tab — is still not readable by another tenant: the key alone is
 * not sufficient authority.
 */
public interface FileStoragePort {

  /**
   * Copies a file into storage under the tenant and document.
   *
   * @return the storage key to persist, plus the content hash computed while
   *     copying so the file is only read once
   */
  StoredFile store(TenantContext tenant, UUID documentId, int version, Path source);

  /** Resolves a key for reading, refusing a key that belongs to another tenant. */
  Path pathOf(TenantContext tenant, String storageKey);

  void delete(TenantContext tenant, String storageKey);

  /** Removes every stored file for a document. */
  void deleteByDocument(TenantContext tenant, UUID documentId);

  record StoredFile(String storageKey, String sha256, long sizeBytes) {}
}
