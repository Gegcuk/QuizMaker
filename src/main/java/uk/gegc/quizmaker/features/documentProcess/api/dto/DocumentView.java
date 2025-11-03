package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for document metadata view.
 */
@Schema(name = "DocumentView", description = "Document metadata and processing status")
public record DocumentView(
    @Schema(description = "Document UUID")
    UUID id,
    
    @Schema(description = "Original filename", example = "sample-doc.pdf")
    String originalName,
    
    @Schema(description = "MIME type", example = "application/pdf")
    String mime,
    
    @Schema(description = "Document source type", example = "FILE")
    NormalizedDocument.DocumentSource source,
    
    @Schema(description = "Character count of normalized text", example = "15000")
    Integer charCount,
    
    @Schema(description = "Language code", example = "en")
    String language,
    
    @Schema(description = "Processing status", example = "NORMALIZED")
    NormalizedDocument.DocumentStatus status,
    
    @Schema(description = "Creation timestamp")
    Instant createdAt,
    
    @Schema(description = "Last update timestamp")
    Instant updatedAt
) {}
