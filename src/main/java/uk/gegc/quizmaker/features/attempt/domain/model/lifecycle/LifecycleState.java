package uk.gegc.quizmaker.features.attempt.domain.model.lifecycle;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleStateException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleStateException.require;

/** Storage metadata, separate from the pure transition contract and durable command receipts. */
public record LifecycleState(UUID attemptId, long rowVersion, State state, Instant createdAt,
                             Instant updatedAt, Instant startedAt, Instant submittedAt) implements LifecycleRead {
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    public LifecycleState {
        require(attemptId != null && rowVersion >= 0 && state != null, INVALID_INPUT);
        createdAt = time(createdAt);
        updatedAt = time(updatedAt);
        if (startedAt != null) startedAt = time(startedAt);
        if (submittedAt != null) submittedAt = time(submittedAt);
        require(!updatedAt.isBefore(createdAt), INVALID_INPUT);
        require((state.execution() == Execution.CREATED) == (startedAt == null), INVALID_INPUT);
        require(state.terminal() == (submittedAt != null), INVALID_INPUT);
        if (startedAt != null) require(!startedAt.isBefore(createdAt) && !startedAt.isAfter(updatedAt), INVALID_INPUT);
        if (submittedAt != null) require(!submittedAt.isBefore(startedAt) && !submittedAt.isAfter(updatedAt), INVALID_INPUT);
    }

    public static LifecycleState initial(UUID attemptId, Instant at) {
        return new LifecycleState(attemptId, 0, new State(VERSION, Execution.CREATED, AnswerCollection.NOT_STARTED),
                at, at, null, null);
    }

    /**
     * Persists an already authorized/validated next tuple. Guards, actual response/finalization
     * writes and command identity remain the caller's responsibility. Storage prevents regression
     * and terminal rewriting; it cannot prove that an answer was accepted or a receipt committed.
     */
    public LifecycleState advance(State next, Instant at) {
        require(next != null, INVALID_INPUT);
        require(rowVersion < Long.MAX_VALUE, STATE_CONFLICT);
        require(!state.terminal(), INVALID_TRANSITION);
        if (state.execution() == Execution.CREATED) {
            require(next.execution() == Execution.IN_PROGRESS && next.answers() == AnswerCollection.NOT_STARTED,
                    INVALID_TRANSITION);
        } else {
            require(next.execution() == Execution.SUBMITTED || next.execution() == Execution.IN_PROGRESS
                    && next.answers() == AnswerCollection.IN_PROGRESS, INVALID_TRANSITION);
        }
        at = time(at);
        require(!at.isBefore(updatedAt), INVALID_INPUT);
        return new LifecycleState(attemptId, rowVersion + 1, next, createdAt, at,
                startedAt == null ? at : startedAt, next.terminal() ? at : null);
    }

    private static Instant time(Instant value) {
        require(value != null && !value.isBefore(MIN_TIME) && !value.isAfter(MAX_TIME), INVALID_INPUT);
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
