package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(
        name = "AnswerSubmissionRequest",
        description = "Payload for submitting an answer to a specific question"
)
public record AnswerSubmissionRequest(
        @Schema(
                description = "UUID of the question to answer",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        )
        @NotNull(message = "Question ID is required")
        UUID questionId,

        @Schema(
                description = "The actual response payload for the question",
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "{\"answer\":true}"
        )
        @NotNull(message = "Response payload must not be null")
        JsonNode response,

        @Schema(
                description = "Include whether the submitted answer is correct (isCorrect field) in the response. Defaults to false.",
                example = "false"
        )
        Boolean includeCorrectness,

        @Schema(
                description = "Include the correct answer information (correctAnswer field) in the response. Defaults to false.",
                example = "false"
        )
        Boolean includeCorrectAnswer,

        @Schema(
                description = "Include the answer explanation (explanation field) in the response. Defaults to false.",
                example = "false"
        )
        Boolean includeExplanation
) {
    public AnswerSubmissionRequest {
        // Default values for optional fields
        if (includeCorrectness == null) {
            includeCorrectness = false;
        }
        if (includeCorrectAnswer == null) {
            includeCorrectAnswer = false;
        }
        if (includeExplanation == null) {
            includeExplanation = false;
        }
    }
}
