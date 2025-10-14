package uk.gegc.quizmaker.features.quiz.api.dto;

import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for quiz export endpoint.
 * Encapsulates format, scope, filters, and print options.
 */
public record QuizExportRequest(
    @NotNull(message = "Export format is required")
    ExportFormat format,
    
    String scope,
    List<UUID> categoryIds,
    List<String> tags,
    UUID authorId,
    Difficulty difficulty,
    String search,
    List<UUID> quizIds,
    PrintOptions printOptions
) {
    public QuizExportRequest {
        if (scope == null || scope.isBlank()) {
            scope = "public";
        }
        if (printOptions == null) {
            printOptions = PrintOptions.defaults();
        }
    }
}

