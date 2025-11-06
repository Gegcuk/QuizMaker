package uk.gegc.quizmaker.features.result.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(name = "QuizResultSummaryDto", description = "Aggregated statistics and analytics for a quiz")
public record QuizResultSummaryDto(
        @Schema(description = "UUID of the quiz", example = "fbaf5a39-40ba-4511-9428-b1b14936466e")
        UUID quizId,

        @Schema(description = "Total number of completed attempts for this quiz", example = "42")
        Long attemptsCount,

        @Schema(
                description = "Average total score across all completed attempts (raw points, not percentage). " +
                        "The score is calculated by summing individual answer scores based on the quiz's scoring system.",
                example = "75.5"
        )
        Double averageScore,

        @Schema(
                description = "Highest score achieved across all completed attempts (raw points, not percentage)",
                example = "150.0"
        )
        Double bestScore,

        @Schema(
                description = "Lowest score achieved across all completed attempts (raw points, not percentage)",
                example = "25.0"
        )
        Double worstScore,

        @Schema(
                description = "Percentage of attempts that achieved a passing grade (≥50% correct answers). " +
                        "Value ranges from 0.0 to 100.0",
                example = "68.5"
        )
        Double passRate,

        @Schema(description = "Per-question statistics showing how many times each question was asked and answered correctly")
        List<QuestionStatsDto> questionStats
) {
}
