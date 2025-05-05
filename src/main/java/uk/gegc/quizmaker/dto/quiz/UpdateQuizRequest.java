package uk.gegc.quizmaker.dto.quiz;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.util.List;
import java.util.UUID;

public record UpdateQuizRequest(

        @Size(min = 3, max = 100, message = "Title length must be between 3 and 100 characters")
        String title,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        Visibility visibility,
        Difficulty difficulty,

        Boolean isRepetitionEnabled,
        Boolean timerEnabled,

        @Min(value = 1, message = "Estimated time must be at least 1 minute")
        @Max(value = 180, message = "Estimated time must be at most 180 minutes")
        Integer estimatedTime,

        @Min(value = 1, message = "Timer duration must be at least 1 minute")
        @Max(value = 180, message = "Timer duration must be at most 180 minutes")
        Integer timerDuration,

        UUID categoryId,
        List<UUID> tagIds
) {
        public UpdateQuizRequest {
                visibility = (visibility == null ? Visibility.PRIVATE : visibility);
                difficulty = (difficulty == null ? Difficulty.MEDIUM  : difficulty);
                tagIds     = (tagIds     == null ? List.of()          : tagIds);
        }
}
