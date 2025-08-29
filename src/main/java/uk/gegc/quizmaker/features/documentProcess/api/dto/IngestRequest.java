package uk.gegc.quizmaker.features.documentProcess.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for ingesting text content directly.
 */
public record IngestRequest(
    @NotBlank(message = "Text content cannot be blank")
    String text,
    
    @Size(max = 32, message = "Language code must be at most 32 characters")
    String language
) {}
