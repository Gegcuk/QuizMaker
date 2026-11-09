package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(name = "NextAttemptItemRequest", description = "Advance ONE_AT_A_TIME using captured navigation rules. The server selects the next item; no future item ID is accepted.")
public record NextAttemptItemRequest(
        @NotNull @PositiveOrZero @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") Long expectedStateVersion,
        @NotNull @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") Long expectedNavigationVersion) {
    @Override public String toString() { return "NextAttemptItemRequest[redacted]"; }
}
