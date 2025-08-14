package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.util.List;
import java.util.UUID;

@Schema(name = "UpdateQuizRequest", description = "Fields to update on an existing quiz")
public record UpdateQuizRequest(

        @Schema(
                description = "New title of the quiz",
                example = "Updated Quiz Title"
        )
        @Size(min = 3, max = 100, message = "Title length must be between 3 and 100 characters")
        String title,

        @Schema(
                description = "New detailed description",
                example = "A revised description explaining the quiz purpose."
        )
        @Size(max = 1000, message = "Description must be at most 1000 characters long")
        String description,

        @Schema(
                description = "New visibility setting",
                example = "PUBLIC"
        )
        Visibility visibility,

        @Schema(
                description = "New difficulty level",
                example = "HARD"
        )
        Difficulty difficulty,

        @Schema(
                description = "Enable or disable repetition",
                example = "true"
        )
        Boolean isRepetitionEnabled,

        @Schema(
                description = "Enable or disable timer",
                example = "false"
        )
        Boolean timerEnabled,

        @Schema(
                description = "New estimated time in minutes",
                example = "20"
        )
        @Min(value = 1, message = "Estimated time must be at least 1 minute")
        @Max(value = 180, message = "Estimated time must be at most 180 minutes")
        Integer estimatedTime,

        @Schema(
                description = "New timer duration in minutes",
                example = "5"
        )
        @Min(value = 1, message = "Timer duration must be at least 1 minute")
        @Max(value = 180, message = "Timer duration must be at most 180 minutes")
        Integer timerDuration,

        @Schema(
                description = "UUID of the new category",
                example = "d290f1ee-6c54-4b01-90e6-d701748f0851"
        )
        UUID categoryId,

        @Schema(
                description = "List of new tag UUIDs",
                example = "[\"a1b2c3d4-0000-0000-0000-000000000000\", \"e5f6g7h8-1111-1111-1111-111111111111\"]"
        )
        List<UUID> tagIds

) {
}
