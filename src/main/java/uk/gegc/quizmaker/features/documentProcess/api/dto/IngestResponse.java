package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.UUID;

/**
 * Response DTO for document ingestion operations.
 */
@Schema(name = "IngestResponse", description = "Response after document ingestion")
public record IngestResponse(
    @Schema(description = "Document UUID", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,
    
    @Schema(description = "Document status", example = "NORMALIZED")
    NormalizedDocument.DocumentStatus status
) {}
