package com.example.ragpoc.storage;

import com.example.ragpoc.cid.CidContext;
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
import org.springframework.stereotype.Service;

/**
 * Filesystem storage, keyed by cid.
 *
 * <p>Keys are {@code <cidId>/<documentId>/original-v<n>.pdf}. The cid is the
 * first path segment deliberately: it makes ownership visible in the directory
 * listing and lets {@link #pathOf} verify ownership without consulting the
 * database. Stores under a temporary name and moves into place once the copy has
 * succeeded, so an interrupted upload cannot leave a truncated file.
 */
@Service
public class FileStorageService {

  private static final Pattern FILE_NAME = Pattern.compile("original-v\\d+\\.pdf");

  private final Path root;

  public FileStorageService(com.example.ragpoc.config.RagProperties properties) {
    this.root = Path.of(properties.storage().localRoot()).toAbsolutePath().normalize();
  }

  public StoredFile store(CidContext cid, UUID documentId, int version, Path source) {
    String fileName = "original-v" + version + ".pdf";
    String key = cid.cidId() + "/" + documentId + "/" + fileName;

    Path target = root.resolve(cid.cidId().toString()).resolve(documentId.toString());
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
      Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);

      return new StoredFile(key, HexFormat.of().formatHex(digest.digest()), copied);
    } catch (IOException | NoSuchAlgorithmException e) {
      deleteQuietly(temp);
      throw new IllegalStateException("Could not store the uploaded file", e);
    }
  }

  public Path pathOf(CidContext cid, String storageKey) {
    Path resolved = resolve(cid, storageKey);
    if (!Files.isReadable(resolved)) {
      throw new IllegalStateException("Stored file is missing: " + storageKey);
    }
    return resolved;
  }

  public void delete(CidContext cid, String storageKey) {
    deleteQuietly(resolve(cid, storageKey));
  }

  public void deleteByDocument(CidContext cid, UUID documentId) {
    Path directory = root.resolve(cid.cidId().toString()).resolve(documentId.toString()).normalize();
    if (!directory.startsWith(root)) {
      throw new IllegalStateException("Refusing to delete outside the storage root");
    }
    try (var entries = Files.exists(directory) ? Files.list(directory) : null) {
      if (entries != null) {
        entries.forEach(FileStorageService::deleteQuietly);
      }
      Files.deleteIfExists(directory);
    } catch (IOException e) {
      throw new IllegalStateException("Could not delete stored files for document " + documentId, e);
    }
  }

  /**
   * Builds a path from a key, rejecting anything malformed or foreign. Parsing
   * stops {@code ../} escapes; the cid comparison means the caller cannot read a
   * file by presenting another cid's key.
   */
  private Path resolve(CidContext cid, String storageKey) {
    if (storageKey == null || storageKey.isBlank()) {
      throw new IllegalArgumentException("storage key is required");
    }

    String[] parts = storageKey.split("/");
    if (parts.length != 3) {
      throw new IllegalArgumentException("Malformed storage key");
    }

    UUID keyCid = parseUuid(parts[0], "cid");
    parseUuid(parts[1], "document");
    if (!FILE_NAME.matcher(parts[2]).matches()) {
      throw new IllegalArgumentException("Malformed storage key");
    }

    if (!keyCid.equals(cid.cidId())) {
      throw new SecurityException("Storage key belongs to a different cid");
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
      // The index is the source of truth for what is answerable; a leftover
      // file is not a correctness problem for retrieval.
    }
  }

  public record StoredFile(String storageKey, String sha256, long sizeBytes) {}
}
