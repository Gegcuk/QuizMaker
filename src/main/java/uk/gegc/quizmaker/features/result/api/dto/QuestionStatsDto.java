package uk.gegc.quizmaker.features.result.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "QuestionStatsDto", description = "Performance statistics for a single question across all attempts")
public record QuestionStatsDto(
        @Schema(description = "UUID of the question", example = "00229d14-e959-4c5c-9d29-a58068eee3ce")
        UUID questionId,

        @Schema(description = "Number of completed attempts that included this question", example = "42")
        long timesAsked,

        @Schema(description = "Number of times this question was answered correctly", example = "30")
        long timesCorrect,

        @Schema(
                description = "Percentage of correct answers (timesCorrect / timesAsked * 100). Value ranges from 0.0 to 100.0",
                example = "71.4"
        )
        double correctRate
) {
}
