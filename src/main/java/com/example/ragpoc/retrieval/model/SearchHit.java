package com.example.ragpoc.retrieval.model;

import java.util.UUID;

/**
 * A chunk returned from the vector store.
 *
 * <p>{@code cidId} is carried back deliberately: it is what the store actually
 * recorded, independently of what was asked for. The retrieval service compares
 * it against the caller's cid and aborts on any mismatch. That check is the
 * backstop for a filter-construction bug.
 */
public record SearchHit(
    UUID chunkId,
    UUID cidId,
    UUID documentId,
    int version,
    String sectionTitle,
    String docTitle,
    String text,
    double score) {}
