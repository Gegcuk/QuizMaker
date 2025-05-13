package uk.gegc.quizmaker.dto.attempt;

import uk.gegc.quizmaker.model.attempt.AttemptMode;
import uk.gegc.quizmaker.model.attempt.AttemptStatus;

import java.time.Instant;
import java.util.UUID;

public record AttemptDto(
        UUID attemptId,
        UUID quizId,
        UUID userId,
        Instant startedAt,
        AttemptStatus status,
        AttemptMode mode) {
}
