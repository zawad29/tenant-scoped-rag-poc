package com.example.ragpoc.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.document.service.DocumentService;
import com.example.ragpoc.support.IntegrationTestSupport;
import com.example.ragpoc.user.entity.Role;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Document cid scoping over the real database and vector store.
 *
 * <p>Rows are seeded directly rather than through {@code upload}, so this test
 * needs no ONNX models and a service bug cannot make it pass.
 */
@SpringBootTest
class DocumentIsolationIT extends IntegrationTestSupport {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry);
  }

  @Autowired private DocumentService documents;
  @Autowired private DataSource dataSource;
  @Autowired private PasswordEncoder passwordEncoder;

  private CidContext cidA;
  private CidContext cidB;

  @BeforeEach
  void seed() {
    resetDatabase(dataSource);
    UUID userA = seedCidWithUser(dataSource, passwordEncoder, "Canary A", "a@canary.test", "pw", "CID_ADMIN");
    UUID userB = seedCidWithUser(dataSource, passwordEncoder, "Canary B", "b@canary.test", "pw", "CID_ADMIN");
    cidA = CidContext.of(cidIdOf(dataSource, "a@canary.test"), userA, Role.CID_ADMIN);
    cidB = CidContext.of(cidIdOf(dataSource, "b@canary.test"), userB, Role.CID_ADMIN);
  }

  @Test
  @DisplayName("a document is visible only to its own cid")
  void documentIsVisibleOnlyToItsOwnCid() {
    UUID document = seedDocument(cidA);

    assertThat(documents.list(cidA)).extracting(DocumentSummary::id).containsExactly(document);
    assertThat(documents.list(cidB)).isEmpty();
    assertThat(documents.findById(cidB, document)).isEmpty();
  }

  @Test
  @DisplayName("another cid's document id is reported as absent, not forbidden")
  void crossCidLookupIsNotFound() {
    UUID document = seedDocument(cidA);

    assertThatThrownBy(() -> documents.require(cidB, document))
        .isInstanceOf(DocumentNotFoundException.class);
  }

  @Test
  @DisplayName("deleting through the wrong cid does nothing; through the right one it removes the document")
  void deleteIsCidScoped() {
    UUID document = seedDocument(cidA);

    assertThatThrownBy(() -> documents.delete(cidB, document))
        .isInstanceOf(DocumentNotFoundException.class);
    assertThat(documents.findById(cidA, document)).isPresent();

    documents.delete(cidA, document);
    assertThat(documents.findById(cidA, document)).isEmpty();
    assertThat(documents.list(cidA)).isEmpty();
  }

  private UUID seedDocument(CidContext cid) {
    UUID documentId = UUID.randomUUID();
    JdbcClient jdbc = JdbcClient.create(dataSource);
    jdbc.sql(
            """
            INSERT INTO document (id, cid_id, title, original_filename, content_hash, status,
                                  current_version, page_count, updated_at)
            VALUES (:id, :cidId, 'Seeded policy', 'policy.pdf', 'hash', 'ACTIVE', 1, 1, now())
            """)
        .param("id", documentId)
        .param("cidId", cid.cidId())
        .update();
    jdbc.sql(
            """
            INSERT INTO document_version (id, document_id, version, storage_key, content_hash, chunk_count)
            VALUES (:id, :documentId, 1, :storageKey, 'hash', 0)
            """)
        .param("id", UUID.randomUUID())
        .param("documentId", documentId)
        .param("storageKey", cid.cidId() + "/" + documentId + "/original-v1.pdf")
        .update();
    return documentId;
  }
}
