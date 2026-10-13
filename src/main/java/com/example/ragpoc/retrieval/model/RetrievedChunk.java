package com.example.ragpoc.retrieval.model;

import java.util.UUID;

/**
 * A chunk selected for the prompt, with a stable citation identifier.
 *
 * @param sourceId the label the model must cite, {@code S1}, {@code S2}, ...
 * @param relevance {@code sigmoid(rerankScore)} for display; the gate compares
 *     raw scores
 */
public record RetrievedChunk(
    String sourceId,
    UUID chunkId,
    UUID documentId,
    int version,
    String sectionTitle,
    String docTitle,
    String text,
    double denseScore,
    double rerankScore,
    double relevance) {}
