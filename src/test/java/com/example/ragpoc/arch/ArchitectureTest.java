package com.example.ragpoc.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.ragpoc.port.VectorIndexPort;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules that keep the isolation design honest.
 *
 * <p>The specification's central claim is that there is exactly one choke point
 * for knowledge access, and that no tenant-free path exists. That claim is only
 * as good as its enforcement: a single future import of a vector store into a
 * controller would quietly create a second path. These rules fail the build
 * instead.
 */
class ArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.ragpoc");
  }

  @Test
  @DisplayName("ports are independent of the adapters that implement them")
  void portsDoNotDependOnAdapters() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..port..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .because(
                "the application depends on ports; only configuration wires concrete adapters")
            .allowEmptyShould(true);

    rule.check(classes);
  }

  @Test
  @DisplayName("ONNX Runtime and the tokenizer library stay inside adapters")
  void onnxIsConfinedToAdapters() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("..adapter..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("ai.onnxruntime..", "ai.djl..")
            .because("model runtime details must not leak into domain, service or web code")
            .allowEmptyShould(true);

    rule.check(classes);
  }

  @Test
  @DisplayName("Spring AI's VectorStore abstraction is never used")
  void springAiVectorStoreIsNotUsed() {
    ArchRule rule =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.ai.vectorstore..")
            .because(
                "it cannot express the mandatory tenant and active-version pre-filter; "
                    + "all vector access goes through VectorIndexPort")
            .allowEmptyShould(true);

    rule.check(classes);
  }

  @Test
  @DisplayName("Qdrant types are confined to the Qdrant adapter")
  void qdrantIsConfinedToItsAdapter() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("..adapter.vector.qdrant..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("io.qdrant..")
            .because("the second vector store must stay behind VectorIndexPort")
            .allowEmptyShould(true);

    rule.check(classes);
  }

  @Test
  @DisplayName("the web layer does not reach into adapters directly")
  void webDoesNotDependOnAdapters() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter..")
            .because("controllers must go through services, which enforce tenancy and guardrails")
            .allowEmptyShould(true);

    rule.check(classes);
  }

  @Test
  @DisplayName("only RetrievalService may search the vector index")
  void searchingGoesThroughOneChokePoint() {
    // Writing to the index is a different concern from reasoning over it. The
    // ingestion pipeline must be able to upsert and delete; what must not exist
    // is a second way to *read* knowledge for answering a question, because
    // that is where the tenant verification and the relevance gate live.
    //
    // Narrowed to the search methods rather than the whole port for exactly that
    // reason: a rule that also banned upsert would be satisfied only by
    // indirection, not by safety.
    DescribedPredicate<JavaMethodCall> searchesTheVectorIndex =
        new DescribedPredicate<>("search the vector index") {
          @Override
          public boolean test(JavaMethodCall call) {
            if (!call.getTargetOwner().isEquivalentTo(VectorIndexPort.class)) {
              return false;
            }
            return switch (call.getName()) {
              case "denseSearch", "keywordSearch" -> true;
              default -> false;
            };
          }
        };

    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackages("..retrieval..", "..adapter..", "..config..")
            .should()
            .callMethodWhere(searchesTheVectorIndex)
            .because(
                "RetrievalService is the single place where search results are tenant-verified "
                    + "and passed through the relevance gate")
            .allowEmptyShould(true);

    rule.check(classes);
  }
}
