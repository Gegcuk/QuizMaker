package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(name = "BulkQuizUpdateRequest", description = "Request payload for updating multiple quizzes")
public record BulkQuizUpdateRequest(
        @Schema(description = "List of quiz IDs to update", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Size(min = 1, message = "At least one quizId must be provided")
        List<UUID> quizIds,

        @Schema(description = "Fields to update for all specified quizzes", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Valid
        UpdateQuizRequest update
) {
}
