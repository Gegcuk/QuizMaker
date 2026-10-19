package uk.gegc.quizmaker.features.quiz.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "QuizDto", description = "Representation of a quiz")
public record QuizDto(

        @Schema(description = "Quiz UUID", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID id,

        @Schema(description = "Creator user UUID", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID creatorId,

        @Schema(description = "Category UUID", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID categoryId,

        @Schema(description = "Title of the quiz", example = "My Sample Quiz")
        String title,

        @Schema(description = "Description", example = "This quiz covers general knowledge questions.")
        String description,

        @Schema(description = "Visibility", example = "PRIVATE")
        Visibility visibility,

        @Schema(description = "Difficulty", example = "MEDIUM")
        Difficulty difficulty,

        @Schema(description = "Quiz status", example = "DRAFT")
        QuizStatus status,

        @Schema(description = "Estimated time in minutes", example = "15")
        Integer estimatedTime,

        @Schema(description = "Is repetition enabled?", example = "false")
        Boolean isRepetitionEnabled,

        @Schema(description = "Is timer enabled?", example = "true")
        Boolean timerEnabled,

        @Schema(description = "Timer duration in minutes", example = "10")
        Integer timerDuration,

        @Schema(
                description = "List of tag UUIDs attached to the quiz",
                example = "[\"a1b2c3d4-0000-0000-0000-000000000000\"]"
        )
        List<UUID> tagIds,

        @Schema(description = "Number of questions in the quiz", example = "10")
        Integer questionCount,

        @Schema(description = "Timestamp when the quiz was created", example = "2025-05-01T15:30:00Z")
        Instant createdAt,

        @Schema(description = "Timestamp when the quiz was last updated", example = "2025-05-10T12:00:00Z")
        Instant updatedAt,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Private owner freshness, present only in scope=me. True only for newly completed AI quizzes until explicit acknowledgement; historical/manual/imported quizzes are false. GET never acknowledges.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, accessMode = Schema.AccessMode.READ_ONLY, example = "true")
        Boolean isNew

) {
    public QuizDto(UUID id, UUID creatorId, UUID categoryId, String title, String description,
                   Visibility visibility, Difficulty difficulty, QuizStatus status, Integer estimatedTime,
                   Boolean isRepetitionEnabled, Boolean timerEnabled, Integer timerDuration,
                   List<UUID> tagIds, Integer questionCount, Instant createdAt, Instant updatedAt) {
        this(id, creatorId, categoryId, title, description, visibility, difficulty, status, estimatedTime,
                isRepetitionEnabled, timerEnabled, timerDuration, tagIds, questionCount, createdAt, updatedAt, null);
    }
}
