package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

@Schema(name = "NavigateAttemptRequest", description = "Online navigation using immutable form-item identity. No client policy or cursor is accepted.")
public record NavigationRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID formItemId,
        @NotNull @PositiveOrZero @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") Long expectedStateVersion,
        @Positive @Schema(minimum = "1", nullable = true, description = "Required for SEQUENTIAL; absent/null for FREE. Use the last saved position version.") Long expectedNavigationVersion) {
    @Override public String toString() { return "NavigationRequest[redacted]"; }
}
