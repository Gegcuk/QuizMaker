package uk.gegc.quizmaker.dto.attempt;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "QuestionTimingStatsDto", description = "Timing statistics for individual questions")
public record QuestionTimingStatsDto(
        @Schema(description = "Question UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID questionId,

        @Schema(description = "Question type", example = "MCQ_SINGLE")
        QuestionType questionType,

        @Schema(description = "Question difficulty", example = "MEDIUM")
        Difficulty difficulty,

        @Schema(description = "Time spent on this question", example = "PT2M30S")
        Duration timeSpent,

        @Schema(description = "Whether the answer was correct", example = "true")
        Boolean isCorrect,

        @Schema(description = "When the question was first shown", example = "2025-01-27T10:30:00Z")
        Instant questionStartedAt,

        @Schema(description = "When the answer was submitted", example = "2025-01-27T10:32:30Z")
        Instant answeredAt
) {
} 