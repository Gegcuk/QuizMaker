package uk.gegc.quizmaker.features.quiz.api.dto.export;

import uk.gegc.quizmaker.features.question.domain.model.Difficulty;

import java.util.List;
import java.util.UUID;

/**
 * Filter DTO for quiz export queries.
 * Supports filtering by multiple criteria.
 */
public record QuizExportFilter(
    List<UUID> categoryIds,
    List<String> tags,
    UUID authorId,
    Difficulty difficulty,
    String scope,
    String search,
    List<UUID> quizIds
) {
    public QuizExportFilter {
        if (scope == null || scope.isBlank()) {
            scope = "public";
        }
    }
}

