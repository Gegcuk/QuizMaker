package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Payload to toggle quiz visibility")
public record VisibilityUpdateRequest(
        @Schema(
                description = "true → make the quiz PUBLIC, false → make it PRIVATE",
                example = "true"
        )
        @NotNull Boolean isPublic
) {
}

