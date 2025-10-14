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
 * All fields are optional except format and can be bound from query parameters.
 */
public record QuizExportRequest(
    @NotNull(message = "Export format is required")
    ExportFormat format,
    
    // Scope and filters
    String scope,
    List<UUID> categoryIds,
    List<String> tags,
    UUID authorId,
    Difficulty difficulty,
    String search,
    List<UUID> quizIds,
    
    // Print options (flattened for query parameter binding)
    Boolean includeCover,
    Boolean includeMetadata,
    Boolean answersOnSeparatePages,
    Boolean includeHints,
    Boolean includeExplanations,
    Boolean groupQuestionsByType
) {
    public QuizExportRequest {
        if (scope == null || scope.isBlank()) {
            scope = "public";
        }
    }
    
    /**
     * Converts request fields to PrintOptions value object
     */
    public PrintOptions toPrintOptions() {
        return new PrintOptions(
            includeCover != null ? includeCover : true,
            includeMetadata != null ? includeMetadata : true,
            answersOnSeparatePages != null ? answersOnSeparatePages : true,
            includeHints != null ? includeHints : false,
            includeExplanations != null ? includeExplanations : false,
            groupQuestionsByType != null ? groupQuestionsByType : false
        );
    }
}

