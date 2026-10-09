package com.example.ragpoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Multi-tenant RAG chatbot PoC.
 *
 * <p>Architectural rule enforced by {@code ArchitectureTest}: all knowledge
 * access flows through a single retrieval choke point that requires a tenant
 * context. See docs/architecture.md.
 */
@SpringBootApplication
public class RagPocApplication {

  public static void main(String[] args) {
    SpringApplication.run(RagPocApplication.class, args);
  }
}
