package uk.gegc.quizmaker.features.quiz.api.dto.imports;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;

import java.util.UUID;

@Schema(name = "QuestionImportDto", description = "Import DTO aligned with exported question structure")
public record QuestionImportDto(
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
    public QuestionImportDto(
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
