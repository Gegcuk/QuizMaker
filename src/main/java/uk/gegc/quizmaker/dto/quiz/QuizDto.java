package uk.gegc.quizmaker.dto.quiz;

import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record QuizDto(
        UUID id,
        UUID creatorId,
        UUID categoryId,
        String title,
        String description,
        Visibility visibility,
        Difficulty difficulty,
        Integer estimatedTime,
        Boolean isRepetitionEnabled,
        Boolean timerEnabled,
        Integer timerDuration,
        List<UUID> tagIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
