package uk.gegc.quizmaker.features.result.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SpacedRepetitionDto(
        UUID entryId,
        UUID questionId,
        Instant nextReviewAt,
        Integer intervalDays,
        Integer repetitionCount,
        Double easeFactor
) {
}
