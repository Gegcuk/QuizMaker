package uk.gegc.quizmaker.features.repetition.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewSourceType;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "RepetitionHistoryDto", description = "A single review event for repetition history")
public record RepetitionHistoryDto(
        @Schema(description = "Review log ID")
        UUID reviewId,
        @Schema(description = "Repetition entry ID")
        UUID entryId,
        @Schema(description = "Content type reviewed")
        RepetitionContentType contentType,
        @Schema(description = "Content ID reviewed")
        UUID contentId,
        @Schema(description = "Grade given by the user")
        RepetitionEntryGrade grade,
        @Schema(description = "Review timestamp (UTC)")
        Instant reviewedAt,
        @Schema(description = "Interval in days after review")
        Integer intervalDays,
        @Schema(description = "Ease factor after review")
        Double easeFactor,
        @Schema(description = "Repetition count after review")
        Integer repetitionCount,
        @Schema(description = "Source type of this review event")
        RepetitionReviewSourceType sourceType,
        @Schema(description = "Source identifier (idempotency key or attempt answer ID)")
        UUID sourceId,
        @Schema(description = "Attempt ID if review originated from an attempt")
        UUID attemptId
) {
}
