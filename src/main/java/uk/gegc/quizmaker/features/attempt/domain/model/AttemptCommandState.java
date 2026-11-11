package uk.gegc.quizmaker.features.attempt.domain.model;

import java.time.Instant;

/** Scalars from the locking read remain current even when its managed entity was already cached. */
public record AttemptCommandState(
        Attempt attempt,
        AttemptStatus status,
        AttemptMode mode,
        Instant startedAt,
        Instant completedAt,
        Double totalScore,
        String acceptedAnswerProjection
) { }
