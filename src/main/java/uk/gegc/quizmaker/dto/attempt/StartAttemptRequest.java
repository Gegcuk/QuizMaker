package uk.gegc.quizmaker.dto.attempt;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.model.attempt.AttemptMode;

@Schema(name = "StartAttemptRequest", description = "Request to start a quiz attempt with a specific mode", example = "{\"mode\":\"ONE_BY_ONE\"}")
public record StartAttemptRequest(
        @Schema(description = "Mode in which to take the attempt", required = true)
        @NotNull(message = "Mode must be provided")
        AttemptMode mode
) {}
