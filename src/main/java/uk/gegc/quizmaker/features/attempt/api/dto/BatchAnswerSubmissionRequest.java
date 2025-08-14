package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import uk.gegc.quizmaker.dto.attempt.AnswerSubmissionRequest;

import java.util.List;

@Schema(name = "BatchAnswerSubmissionRequest", description = "Payload for submitting multiple answers at once")
public record BatchAnswerSubmissionRequest(
        @Schema(description = "List of answers to submit", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty(message = "At least one answer must be submitted")
        List<@Valid AnswerSubmissionRequest> answers
) {
}