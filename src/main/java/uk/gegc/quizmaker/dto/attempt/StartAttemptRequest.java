package uk.gegc.quizmaker.dto.attempt;

import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.model.attempt.AttemptMode;

public record StartAttemptRequest(
        @NotNull(message = "Mode must be provided")
        AttemptMode mode
) {
}
