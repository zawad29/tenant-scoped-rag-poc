package com.example.ragpoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CID-scoped RAG chatbot PoC.
 *
 * <p>All knowledge access flows through a single retrieval choke point that
 * requires a cid context.
 */
@SpringBootApplication
public class RagPocApplication {

  public static void main(String[] args) {
    SpringApplication.run(RagPocApplication.class, args);
  }
}
