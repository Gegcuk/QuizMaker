package uk.gegc.quizmaker.features.result.domain.model.release;

import java.time.*;
import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

/** Durable internal work, not a participant grant, lease, timer or mutable result. */
public record ScheduledRelease(UUID submissionId, UUID resultId, UUID configurationId, String configurationHash,
        UUID eventId, Instant dueAt, Instant registeredAt, State state, int failures, long rowVersion,
        Instant nextAt, ResultReleaseException.Reason lastFailure) {
    public enum State { PENDING, RETRY, REVIEW, RELEASED }
    public ScheduledRelease {
        require(submissionId != null && resultId != null && configurationId != null && eventId != null
                && configurationHash != null && configurationHash.matches("[0-9a-f]{64}")
                && dueAt != null && registeredAt != null && state != null && failures >= 0 && failures <= 4
                && rowVersion >= failures && ((failures == 0) == (lastFailure == null)), CORRUPT);
        require(state == State.PENDING ? failures == 0 && lastFailure == null && dueAt.equals(nextAt)
                : state == State.RETRY ? failures >= 1 && failures <= 3 && lastFailure != null
                    && nextAt != null && !nextAt.isBefore(dueAt)
                : state == State.REVIEW ? failures >= 1 && lastFailure != null && nextAt == null
                : nextAt == null, CORRUPT);
    }
    /** Q60–Q62: three retries AFTER the initial failure; unknown commits are reconciled first. */
    public ScheduledRelease failed(ResultReleaseException.Reason reason, Instant at) {
        require(state == State.PENDING || state == State.RETRY, CORRUPT);
        int count = failures + 1;
        boolean temporary = reason == BUSY || reason == RETRYABLE || reason == UNAVAILABLE;
        boolean retry = temporary && count <= 3;
        Instant next = retry ? at.plus(Duration.ofMinutes(switch (count) { case 1 -> 1; case 2 -> 5; default -> 15; })) : null;
        if (next != null && next.isBefore(dueAt)) next = dueAt;
        return new ScheduledRelease(submissionId, resultId, configurationId, configurationHash, eventId, dueAt,
                registeredAt, retry ? State.RETRY : State.REVIEW, count, rowVersion + 1, next, reason);
    }
    public ScheduledRelease released() {
        require(state == State.PENDING || state == State.RETRY, CORRUPT);
        return new ScheduledRelease(submissionId, resultId, configurationId, configurationHash, eventId, dueAt,
                registeredAt, State.RELEASED, failures, rowVersion + 1, null, lastFailure);
    }
    @Override public String toString() { return "ScheduledRelease[state=" + state + ",failures=" + failures + "]"; }
}
