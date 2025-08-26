package uk.gegc.quizmaker.features.document.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentNodeDto(
        UUID id,
        UUID documentId,
        UUID parentId,
        Integer level,
        DocumentNode.NodeType type,
        String title,
        Integer startOffset,
        Integer endOffset,
        String startAnchor,
        String endAnchor,
        Integer ordinal,
        DocumentNode.Strategy strategy,
        BigDecimal confidence,
        String sourceVersionHash,
        Instant createdAt,
        List<DocumentNodeDto> children
) {
    // Empty constructor for Jackson
    public DocumentNodeDto {
        if (level == null || level < 0) {
            throw new IllegalArgumentException("Level must be non-negative");
        }
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }
        if (startOffset == null || startOffset < 0) {
            throw new IllegalArgumentException("Start offset must be non-negative");
        }
        if (endOffset == null || endOffset < 0) {
            throw new IllegalArgumentException("End offset must be non-negative");
        }
        if (ordinal == null || ordinal < 0) {
            throw new IllegalArgumentException("Ordinal must be non-negative");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy cannot be null");
        }
        if (sourceVersionHash == null || sourceVersionHash.trim().isEmpty()) {
            throw new IllegalArgumentException("Source version hash cannot be null or empty");
        }
        if (children == null) {
            throw new IllegalArgumentException("Children cannot be null");
        }
    }
}
