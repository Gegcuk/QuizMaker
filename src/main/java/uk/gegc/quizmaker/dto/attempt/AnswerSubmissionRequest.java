package uk.gegc.quizmaker.dto.attempt;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AnswerSubmissionRequest(
        @NotNull(message = "Question ID is required")
        UUID questionId,

        @NotNull(message = "Response payload must not be null")
        JsonNode response
) {
}
