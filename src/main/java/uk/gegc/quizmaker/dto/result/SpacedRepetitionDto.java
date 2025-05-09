package uk.gegc.quizmaker.dto.result;

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
