package uk.gegc.quizmaker.features.quiz.api.dto;

import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.projection.QuizSummaryProjection;

import java.time.Instant;
import java.util.UUID;


public record QuizSummaryDto(
    UUID id,
    String title,
    String description,
    Instant createdAt,
    Instant updatedAt,
    QuizStatus status,
    Visibility visibility,
    String creatorUsername,
    UUID creatorId,
    String categoryName,
    UUID categoryId,
    Long questionCount,
    Long tagCount,
    Integer estimatedTime  // Matches entity field name
) {
    /**
     * Factory method to create from projection.
     * No type conversion needed - projection already returns correct types.
     */
    public static QuizSummaryDto fromProjection(
            QuizSummaryProjection projection) {
        return new QuizSummaryDto(
            projection.getId(),
            projection.getTitle(),
            projection.getDescription(),
            projection.getCreatedAt(),
            projection.getUpdatedAt(),
            projection.getStatus(),
            projection.getVisibility(),
            projection.getCreatorUsername(),
            projection.getCreatorId(),  // Already UUID, no conversion needed
            projection.getCategoryName(),
            projection.getCategoryId(),
            projection.getQuestionCount(),
            projection.getTagCount(),
            projection.getEstimatedTime()
        );
    }
}

