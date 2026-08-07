package uk.gegc.quizmaker.shared.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Minimal application liveness response")
public record HealthStatusResponse(
        @Schema(description = "Application liveness status", example = "UP", allowableValues = {"UP", "DOWN"})
        String status
) {
}
