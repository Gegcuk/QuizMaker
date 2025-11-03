package uk.gegc.quizmaker.features.documentProcess.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Response DTO for node-based extraction.
 * Contains the extracted text along with metadata about the node and its boundaries.
 */
@Schema(name = "ExtractResponse", description = "Extracted content for a specific structural node")
public record ExtractResponse(
    @Schema(description = "Document UUID")
    UUID documentId,
    
    @Schema(description = "Node UUID")
    UUID nodeId,
    
    @Schema(description = "Node title (e.g., chapter name)", example = "Chapter 1: Introduction")
    String title,
    
    @Schema(description = "Start offset in document text (inclusive)", example = "0")
    int start,
    
    @Schema(description = "End offset in document text (exclusive)", example = "5000")
    int end,
    
    @Schema(description = "Extracted text content for this node")
    String text
) {}
