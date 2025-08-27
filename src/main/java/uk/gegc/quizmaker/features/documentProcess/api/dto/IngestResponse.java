package uk.gegc.quizmaker.features.documentProcess.api.dto;

import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.UUID;

/**
 * Response DTO for document ingestion operations.
 */
public record IngestResponse(
    UUID id,
    NormalizedDocument.DocumentStatus status
) {}
