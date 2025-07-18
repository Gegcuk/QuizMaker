package uk.gegc.quizmaker.dto.attempt;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(name = "BatchAnswerSubmissionRequest", description = "Payload for submitting multiple answers at once")
public record BatchAnswerSubmissionRequest(
        @Schema(description = "List of answers to submit", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "At least one answer must be submitted")
        List<@Valid AnswerSubmissionRequest> answers
) {
}