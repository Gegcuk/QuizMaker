package uk.gegc.quizmaker.features.quiz.api.dto.export;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.util.UUID;

/**
 * Export DTO for Question entity.
 * Preserves question structure with JSON content for round-trip compatibility.
 */
public record QuestionExportDto(
    UUID id,
    QuestionType type,
    Difficulty difficulty,
    String questionText,
    JsonNode content,
    String hint,
    String explanation,
    String attachmentUrl,
    MediaRefDto attachment
) {
    public QuestionExportDto(
            UUID id,
            QuestionType type,
            Difficulty difficulty,
            String questionText,
            JsonNode content,
            String hint,
            String explanation,
            String attachmentUrl
    ) {
        this(id, type, difficulty, questionText, content, hint, explanation, attachmentUrl, null);
    }
}
