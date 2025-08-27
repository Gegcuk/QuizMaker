package uk.gegc.quizmaker.features.documentProcess.api.dto;

import java.util.UUID;

/**
 * Response DTO for text slice extraction.
 */
public record TextSliceResponse(
    UUID documentId,
    int start,
    int end,
    String text
) {}
