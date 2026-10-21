package uk.gegc.quizmaker.features.quiz.application.query;

import java.time.Instant;
import java.util.Objects;

/** A volatile, read-only snapshot of admission order, never a completion estimate. */
public record GenerationQueuePosition(long queuePosition, Instant asOf) {
    public GenerationQueuePosition {
        if (queuePosition < 1) throw new IllegalArgumentException("Queue position must be positive");
        Objects.requireNonNull(asOf, "asOf must not be null");
    }

    public long jobsAhead() {
        return queuePosition - 1;
    }
}
