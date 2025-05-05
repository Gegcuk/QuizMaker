package uk.gegc.quizmaker.dto.quiz;

import jakarta.validation.constraints.*;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.util.List;
import java.util.UUID;


public record CreateQuizRequest (
        @NotBlank(message = "Title must not be blank")
        @Size(min = 3, max = 100, message = "Title length must be between 3 and 100 characters")
        String title,

        @Size(max = 1000, message = "Description must be at most 1000 characters long")
        String description,

        Visibility visibility,
        Difficulty difficulty,
        boolean isRepetitionEnabled,
        boolean timerEnabled,

        @Min(value = 1, message = "Estimated time can't be less than 1 minute")
        @Max(value = 180, message = "Estimated time can't be more than 180 minutes")
        int estimatedTime,

        @Min(value = 1, message = "Timer duration must be at least 1 minute")
        @Max(value = 180, message = "Timer duration must be at most 180 minutes")
        int timerDuration,

        UUID categoryId,
        List<UUID> tagIds
) {
    public CreateQuizRequest {
        visibility = (visibility == null ? Visibility.PRIVATE : visibility);
        difficulty = (difficulty == null ? Difficulty.MEDIUM : difficulty);
        tagIds = (tagIds == null ? List.of() : tagIds);
    }
}
