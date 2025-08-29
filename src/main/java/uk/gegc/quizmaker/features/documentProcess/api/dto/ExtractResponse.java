package uk.gegc.quizmaker.features.documentProcess.api.dto;

import java.util.UUID;

/**
 * Response DTO for node-based extraction.
 * Contains the extracted text along with metadata about the node and its boundaries.
 */
public record ExtractResponse(
    UUID documentId,
    UUID nodeId,
    String title,
    int start,
    int end,
    String text
) {}
