package uk.gegc.quizmaker.dto.attempt;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record BatchAnswerSubmissionRequest(
        @NotEmpty(message = "At least one answer must be submitted")
        List<@Valid AnswerSubmissionRequest> answers
) {
}
