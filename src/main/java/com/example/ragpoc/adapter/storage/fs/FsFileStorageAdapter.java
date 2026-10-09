package com.example.ragpoc.adapter.storage.fs;

import com.example.ragpoc.port.FileStoragePort;
import com.example.ragpoc.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filesystem storage, keyed by tenant.
 *
 * <p>Keys are {@code <tenantId>/<documentId>/original-v<n>.pdf}. The tenant is
 * the first path segment deliberately: it makes the ownership of a file visible
 * in the directory listing, and it lets
 * {@link #pathOf(TenantContext, String)} verify ownership without consulting
 * the database.
 *
 * <p>Stores the file under a temporary name and moves it into place once the
 * copy has succeeded, so a failed or interrupted upload cannot leave a
 * truncated file that looks like a valid one. It is a local move within the same
 * filesystem, so the atomicity is real rather than nominal.
 */
public class FsFileStorageAdapter implements FileStoragePort {

  /** Validates a key segment before any of it is used to build a path. */
  private static final Pattern FILE_NAME = Pattern.compile("original-v\\d+\\.pdf");

  private final Path root;

  public FsFileStorageAdapter(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public StoredFile store(TenantContext tenant, UUID documentId, int version, Path source) {
    String fileName = "original-v" + version + ".pdf";
    String key = tenant.tenantId() + "/" + documentId + "/" + fileName;

    Path target = root.resolve(tenant.tenantId().toString()).resolve(documentId.toString());
    Path temp = null;
    try {
      Files.createDirectories(target);
      temp = Files.createTempFile(target, ".upload-", ".part");

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long copied;
      try (InputStream in = Files.newInputStream(source);
          DigestInputStream digesting = new DigestInputStream(in, digest);
          OutputStream out = Files.newOutputStream(temp)) {
        copied = digesting.transferTo(out);
      }

      Path destination = target.resolve(fileName).normalize();
      // move with REPLACE_EXISTING: re-uploading the same version overwrites it.
      Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);

      return new StoredFile(key, HexFormat.of().formatHex(digest.digest()), copied);
    } catch (IOException | NoSuchAlgorithmException e) {
      deleteQuietly(temp);
      throw new IllegalStateException("Could not store the uploaded file", e);
    }
  }

  @Override
  public Path pathOf(TenantContext tenant, String storageKey) {
    Path resolved = resolve(tenant, storageKey);
    if (!Files.isReadable(resolved)) {
      throw new IllegalStateException("Stored file is missing: " + storageKey);
    }
    return resolved;
  }

  @Override
  public void delete(TenantContext tenant, String storageKey) {
    deleteQuietly(resolve(tenant, storageKey));
  }

  @Override
  public void deleteByDocument(TenantContext tenant, UUID documentId) {
    Path directory = root.resolve(tenant.tenantId().toString()).resolve(documentId.toString()).normalize();
    if (!directory.startsWith(root)) {
      throw new IllegalStateException("Refusing to delete outside the storage root");
    }
    try (var entries = Files.exists(directory) ? Files.list(directory) : null) {
      if (entries != null) {
        entries.forEach(FsFileStorageAdapter::deleteQuietly);
      }
      Files.deleteIfExists(directory);
    } catch (IOException e) {
      throw new IllegalStateException("Could not delete stored files for document " + documentId, e);
    }
  }

  /**
   * Builds a path from a key, rejecting anything malformed or foreign.
   *
   * <p>Both checks matter. Parsing stops {@code ../} sequences from escaping the
   * root; the tenant comparison is the second layer, and it means the caller
   * cannot read a file by presenting another tenant's key.
   */
  private Path resolve(TenantContext tenant, String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      throw new IllegalArgumentException("storage key is required");
    }

    String[] parts = storageKey.split("/");
    if (parts.length != 3) {
      throw new IllegalArgumentException("Malformed storage key");
    }

    UUID keyTenant = parseUuid(parts[0], "tenant");
    parseUuid(parts[1], "document");
    if (!FILE_NAME.matcher(parts[2]).matches()) {
      throw new IllegalArgumentException("Malformed storage key");
    }

    if (!keyTenant.equals(tenant.tenantId())) {
      // A cross-tenant key is a signal, not a typo, so it is logged as a
      // security event by the caller's audit trail.
      throw new SecurityException("Storage key belongs to a different tenant");
    }

    Path resolved = root.resolve(parts[0]).resolve(parts[1]).resolve(parts[2]).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Storage key escapes the storage root");
    }
    return resolved;
  }

  private static UUID parseUuid(String value, String what) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Malformed storage key: " + what + " segment is not a uuid", e);
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Deletion failures are logged by the caller that cares; a leftover file
      // is not a correctness problem for retrieval, because the index is the
      // source of truth for what is answerable.
    }
  }
}
