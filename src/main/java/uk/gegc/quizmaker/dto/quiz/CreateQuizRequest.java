package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.util.List;
import java.util.UUID;

@Schema(name = "CreateQuizRequest", description = "Payload for creating a quiz")
public record CreateQuizRequest(
        @Schema(description = "Title of the quiz", example = "My Quiz")
        @NotBlank(message = "Title must not be blank")
        @Size(min = 3, max = 100, message = "Title length must be between 3 and 100 characters")
        String title,

        @Schema(description = "Detailed description", example = "A fun pop-quiz")
        @Size(max = 1000, message = "Description must be at most 1000 characters long")
        String description,

        @Schema(description = "Visibility of quiz (defaults PRIVATE)")
        Visibility visibility,

        @Schema(description = "Difficulty level (defaults MEDIUM)")
        Difficulty difficulty,

        @Schema(description = "Repetition enabled?")
        boolean isRepetitionEnabled,

        @Schema(description = "Timer enabled?")
        boolean timerEnabled,

        @Schema(description = "Estimated time in minutes", example = "10")
        @Min(value = 1, message = "Estimated time can't be less than 1 minute")
        @Max(value = 180, message = "Estimated time can't be more than 180 minutes")
        int estimatedTime,

        @Schema(description = "Timer duration in minutes", example = "5")
        @Min(value = 1, message = "Timer duration must be at least 1 minute")
        @Max(value = 180, message = "Timer duration must be at most 180 minutes")
        int timerDuration,

        @Schema(description = "Category UUID", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID categoryId,

        @Schema(description = "List of tag UUIDs", example = "[\"a1b2c3d4-...\", \"e5f6g7h8-...\"]")
        List<UUID> tagIds
) {
    public CreateQuizRequest {
        visibility = (visibility == null ? Visibility.PRIVATE : visibility);
        difficulty = (difficulty == null ? Difficulty.MEDIUM : difficulty);
        tagIds = (tagIds == null ? List.of() : tagIds);
    }
}
