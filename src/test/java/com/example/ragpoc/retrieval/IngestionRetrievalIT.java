package com.example.ragpoc.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.document.DocumentSummary;
import com.example.ragpoc.document.service.DocumentService;
import com.example.ragpoc.retrieval.model.RetrievalOutcome;
import com.example.ragpoc.retrieval.service.RetrievalService;
import com.example.ragpoc.support.IntegrationTestSupport;
import com.example.ragpoc.support.ModelAssets;
import com.example.ragpoc.support.PdfFixtures;
import com.example.ragpoc.user.entity.Role;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end synchronous ingestion and retrieval, with the real ONNX models.
 *
 * <p>Skipped when the models are absent (run {@code scripts/fetch-models.sh}).
 * The point of the test is the full path: an upload indexes synchronously, the
 * document is answerable straight after, and another cid cannot retrieve it.
 */
@Tag("onnx")
@SpringBootTest
class IngestionRetrievalIT extends IntegrationTestSupport {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registerProperties(registry);
  }

  @BeforeAll
  static void requireModels() {
    assumeTrue(ModelAssets.available(), ModelAssets.missingMessage());
  }

  @Autowired private DocumentService documents;
  @Autowired private RetrievalService retrieval;
  @Autowired private DataSource dataSource;
  @Autowired private PasswordEncoder passwordEncoder;

  @TempDir Path tempDir;

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
  @DisplayName("an upload indexes synchronously and is answerable only by its own cid")
  void uploadIsIndexedSynchronouslyAndStaysWithinItsCid() throws IOException {
    Path pdf =
        PdfFixtures.writePdf(
            tempDir,
            "handbook.pdf",
            List.of(
                "The secret canary code for this document is ZEBRA-42.",
                "Annual leave accrues at one and a half days per month."));

    documents.upload(cidA, "Handbook", "handbook.pdf", pdf);

    DocumentSummary summary = documents.list(cidA).getFirst();
    assertThat(summary.status()).isEqualTo("ACTIVE");
    assertThat(summary.chunkCount()).isPositive();

    RetrievalOutcome own = retrieval.retrieve(cidA, "What is the secret canary code?");
    assertThat(own.canAnswer()).isTrue();
    assertThat(own.chunks()).anyMatch(chunk -> chunk.text().contains("ZEBRA-42"));

    RetrievalOutcome other = retrieval.retrieve(cidB, "What is the secret canary code?");
    assertThat(other.canAnswer()).isFalse();
  }
}
