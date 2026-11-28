package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "BranchAdvanceAcknowledgement", description = "Original committed transition metadata, also returned on exact replay.")
public record BranchAdvanceAcknowledgement(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Outcome outcome,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long formVersion,
        @Schema(description = "Delivered form-item UUID. Absent at terminal readiness.") UUID formItemId) {
    public enum Outcome { APPENDED, TERMINAL_READY }

    @Override public String toString() { return "BranchAdvanceAcknowledgement"; }
}
