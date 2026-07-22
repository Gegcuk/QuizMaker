package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(name = "BulkQuizUpdateRequest", description = "Request payload for updating multiple quizzes")
public record BulkQuizUpdateRequest(
        @Schema(
                description = "Quiz UUIDs to update. At least one ID is required; each ID is processed independently.",
                example = "[\"d290f1ee-6c54-4b01-90e6-d701748f0851\"]",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull
        @Size(min = 1, message = "At least one quizId must be provided")
        List<UUID> quizIds,

        @Schema(
                description = "Allowed update fields applied to every requested quiz ID. Omitted fields are unchanged.",
                implementation = UpdateQuizRequest.class,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull
        @Valid
        UpdateQuizRequest update
) {
}
