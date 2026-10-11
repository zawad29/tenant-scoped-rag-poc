package com.example.ragpoc.document;

import java.time.Instant;
import java.util.UUID;

/** A document row for the admin list. */
public record DocumentSummary(
    UUID id,
    String title,
    String originalFilename,
    String status,
    int currentVersion,
    Integer pageCount,
    Integer chunkCount,
    Instant updatedAt) {}
