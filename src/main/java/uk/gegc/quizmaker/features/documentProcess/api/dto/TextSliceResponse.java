package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Response DTO for text slice extraction.
 */
@Schema(name = "TextSliceResponse", description = "Text slice extracted from document")
public record TextSliceResponse(
    @Schema(description = "Document UUID")
    UUID documentId,
    
    @Schema(description = "Start offset (inclusive)", example = "0")
    int start,
    
    @Schema(description = "End offset (exclusive)", example = "1000")
    int end,
    
    @Schema(description = "Extracted text content")
    String text
) {}
