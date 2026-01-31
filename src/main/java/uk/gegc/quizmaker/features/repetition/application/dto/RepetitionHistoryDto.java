package uk.gegc.quizmaker.features.repetition.application.dto;

import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionContentType;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionEntryGrade;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewSourceType;

import java.time.Instant;
import java.util.UUID;

public record RepetitionHistoryDto(
        UUID reviewId,
        UUID entryId,
        RepetitionContentType contentType,
        UUID contentId,
        RepetitionEntryGrade grade,
        Instant reviewedAt,
        Integer intervalDays,
        Double easeFactor,
        Integer repetitionCount,
        RepetitionReviewSourceType sourceType,
        UUID sourceId,
        UUID attemptId
) {
}
