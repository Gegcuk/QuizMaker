package uk.gegc.quizmaker.features.quizgroup.api.dto;

import uk.gegc.quizmaker.features.quizgroup.domain.repository.projection.QuizGroupSummaryProjection;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary DTO for quiz group list views.
 * Lightweight representation with essential fields.
 */
public record QuizGroupSummaryDto(
        UUID id,
        String name,
        String description,
        String color,
        String icon,
        Instant createdAt,
        Instant updatedAt,
        Long quizCount
) {
    /**
     * Factory method to create from domain projection.
     * No type conversion needed - projection already returns correct types.
     */
    public static QuizGroupSummaryDto fromProjection(QuizGroupSummaryProjection projection) {
        return new QuizGroupSummaryDto(
                projection.getId(),
                projection.getName(),
                projection.getDescription(),
                projection.getColor(),
                projection.getIcon(),
                projection.getCreatedAt(),
                projection.getUpdatedAt(),
                projection.getQuizCount()
        );
    }
}

