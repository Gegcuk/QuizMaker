package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(name = "AttemptSubmitRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "Final submit has no answers or policy overrides. Confirm unanswered items using the current saved form before sending.")
public record AttemptSubmitRequest(@NotNull @Min(0) Long expectedStateVersion, @NotNull Boolean completionConfirmed) {
    @Override public String toString() { return "AttemptSubmitRequest[redacted]"; }
}
