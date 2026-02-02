package uk.gegc.quizmaker.features.repetition.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "ReviewResponseDto", description = "Updated schedule after manual review")
public record ReviewResponseDto(
        @Schema(description = "Repetition entry ID")
        UUID entryId,
        @Schema(description = "Next scheduled review time (UTC)")
        Instant nextReviewAt,
        @Schema(description = "Interval in days")
        Integer intervalDays,
        @Schema(description = "Repetition count")
        Integer repetitionCount,
        @Schema(description = "Ease factor (SM-2)")
        Double easeFactor,
        @Schema(description = "Last review timestamp (UTC)")
        Instant lastReviewedAt,
        @Schema(description = "Last review grade")
        RepetitionEntryGrade lastGrade
) {
}
