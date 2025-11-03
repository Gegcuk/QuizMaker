package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for ingesting text content directly.
 */
@Schema(name = "IngestRequest", description = "Request to ingest text content")
public record IngestRequest(
    @Schema(description = "Text content to ingest", example = "This is sample text content for quiz generation")
    @NotBlank(message = "Text content cannot be blank")
    String text,
    
    @Schema(description = "Language code (ISO 639-1)", example = "en")
    @Size(max = 32, message = "Language code must be at most 32 characters")
    String language
) {}
