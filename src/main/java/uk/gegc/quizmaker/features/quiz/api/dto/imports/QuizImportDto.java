package uk.gegc.quizmaker.features.quiz.api.dto.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "QuizImportDto", description = "Import DTO aligned with exported quiz structure")
public record QuizImportDto(
    @Schema(description = "Schema version for import payloads", example = "1")
    Integer schemaVersion,
    UUID id,
    String title,
    String description,
    Visibility visibility,
    Difficulty difficulty,
    Integer estimatedTime,
    List<String> tags,
    String category,
    UUID creatorId,
    List<QuestionImportDto> questions,
    Instant createdAt,
    Instant updatedAt
) {
    public QuizImportDto {
        if (schemaVersion == null) {
            schemaVersion = 1;
        }
    }
}
