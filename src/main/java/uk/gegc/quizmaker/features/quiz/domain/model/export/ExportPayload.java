package uk.gegc.quizmaker.features.quiz.domain.model.export;

import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;

import java.util.List;

/**
 * Payload containing quizzes and options for export rendering.
 * Passed to ExportRenderer implementations.
 */
public record ExportPayload(
    List<QuizExportDto> quizzes,
    PrintOptions printOptions,
    String filenamePrefix
) {
    public ExportPayload {
        if (quizzes == null) {
            throw new IllegalArgumentException("Quizzes list cannot be null");
        }
        if (printOptions == null) {
            printOptions = PrintOptions.defaults();
        }
        if (filenamePrefix == null || filenamePrefix.isBlank()) {
            filenamePrefix = "quizzes_export";
        }
    }
}

