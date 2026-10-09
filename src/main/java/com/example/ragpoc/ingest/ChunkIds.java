package com.example.ragpoc.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Derives chunk identifiers deterministically.
 *
 * <p>Two properties are wanted, and they pull in the same direction:
 *
 * <ul>
 *   <li><b>Retries are safe.</b> Re-running an ingestion job produces the same
 *       ids, so the index upsert overwrites rather than duplicating. Without
 *       this, a job that failed halfway would double every chunk it had already
 *       written.
 *   <li><b>Qdrant accepts them.</b> Qdrant point ids must be an unsigned integer
 *       or a UUID; the obvious {@code documentId:version:index} string form is
 *       rejected by the API. Producing a UUID here means the second adapter does
 *       not need a different identifier scheme.
 * </ul>
 *
 * <p>UUID version 5 (SHA-1 over a namespaced name) rather than Java's
 * {@code UUID.nameUUIDFromBytes}, which is version 3 (MD5). Both would work;
 * version 5 is what the design says and is the stronger hash.
 */
public final class ChunkIds {

  private ChunkIds() {}

  /**
   * The identifier of one chunk.
   *
   * <p>The tenant is part of the name, so the same document uploaded to two
   * tenants produces two distinct ids. If it were omitted, two tenants' chunks
   * would collide on the primary key and the second upsert would overwrite the
   * first — a data-loss bug that would present as cross-tenant contamination.
   */
  public static UUID forChunk(UUID tenantId, UUID documentId, int version, int chunkIndex) {
    String name = tenantId + ":" + documentId + ":" + version + ":" + chunkIndex;
    return uuidV5(name);
  }

  private static UUID uuidV5(String name) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      // A fixed namespace so these ids cannot collide with v5 uuids generated
      // elsewhere for a different purpose.
      sha1.update("rag-poc/chunk".getBytes(StandardCharsets.UTF_8));
      byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));

      hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
      hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // RFC 4122 variant

      long mostSignificant = 0;
      long leastSignificant = 0;
      for (int i = 0; i < 8; i++) {
        mostSignificant = (mostSignificant << 8) | (hash[i] & 0xff);
      }
      for (int i = 8; i < 16; i++) {
        leastSignificant = (leastSignificant << 8) | (hash[i] & 0xff);
      }
      return new UUID(mostSignificant, leastSignificant);
    } catch (NoSuchAlgorithmException e) {
      // SHA-1 is required of every Java platform.
      throw new IllegalStateException("SHA-1 is unavailable", e);
    }
  }
}
