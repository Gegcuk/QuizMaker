package uk.gegc.quizmaker.features.repetition.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;

import java.util.UUID;

@Schema(name = "RepetitionReviewRequest", description = "Manual review request for a repetition entry")
public record RepetitionReviewRequest(
        @Schema(description = "Review grade", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        RepetitionEntryGrade grade,
        @Schema(description = "Optional idempotency key to avoid double-processing")
        UUID idempotencyKey
) {
}
