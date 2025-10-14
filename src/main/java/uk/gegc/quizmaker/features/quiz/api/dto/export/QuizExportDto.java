package uk.gegc.quizmaker.features.quiz.api.dto.export;

import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Export DTO for Quiz entity.
 * Stable structure designed for round-trip import/export.
 * Includes nested questions inline.
 */
public record QuizExportDto(
    UUID id,
    String title,
    String description,
    Visibility visibility,
    Difficulty difficulty,
    Integer estimatedTime,
    List<String> tags,
    String category,
    UUID creatorId,
    List<QuestionExportDto> questions,
    Instant createdAt,
    Instant updatedAt
) {
}

