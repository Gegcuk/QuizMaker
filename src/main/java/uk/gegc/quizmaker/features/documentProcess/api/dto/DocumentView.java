package uk.gegc.quizmaker.features.documentProcess.api.dto;

import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument.DocumentSource;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for document metadata view.
 */
public record DocumentView(
    UUID id,
    String originalName,
    String mime,
    NormalizedDocument.DocumentSource source,
    Integer charCount,
    String language,
    NormalizedDocument.DocumentStatus status,
    Instant createdAt,
    Instant updatedAt
) {}
