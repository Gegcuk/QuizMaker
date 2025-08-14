package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;

@Schema(name = "CurrentQuestionDto", description = "Current question for an existing attempt with progress information")
public record CurrentQuestionDto(
        @Schema(description = "Current question (safe version without correct answers)")
        QuestionForAttemptDto question,

        @Schema(description = "Current question number (1-based)", example = "3")
        int questionNumber,

        @Schema(description = "Total number of questions in the quiz", example = "10")
        int totalQuestions,

        @Schema(description = "Current status of the attempt", example = "IN_PROGRESS")
        AttemptStatus attemptStatus
) {
}