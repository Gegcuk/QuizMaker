package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "AttemptStatsDto", description = "Detailed statistics for a quiz attempt")
public record AttemptStatsDto(
        @Schema(description = "Attempt UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID attemptId,

        @Schema(description = "Total time spent on attempt", example = "PT15M30S")
        Duration totalTime,

        @Schema(description = "Average time per question", example = "PT3M6S")
        Duration averageTimePerQuestion,

        @Schema(description = "Number of questions answered", example = "5")
        Integer questionsAnswered,

        @Schema(description = "Number of correct answers", example = "4")
        Integer correctAnswers,

        @Schema(description = "Accuracy percentage", example = "80.0")
        Double accuracyPercentage,

        @Schema(description = "Completion percentage", example = "100.0")
        Double completionPercentage,

        @Schema(description = "Individual question timing stats")
        List<QuestionTimingStatsDto> questionTimings,

        @Schema(description = "When the attempt started", example = "2025-01-27T10:30:00Z")
        Instant startedAt,

        @Schema(description = "When the attempt was completed", example = "2025-01-27T10:45:30Z")
        Instant completedAt
) {
} 