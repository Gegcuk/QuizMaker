package uk.gegc.quizmaker.features.attempt.domain.model.trace;

import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException.require;

/** Internal evidence, never authentication, command execution or participant delivery authority. */
public final class ExecutionTrace {
    public static final int VERSION = 1;
    // Repository I/O bound only, not a participant page/session limit or a product allowance.
    public static final int MAX_PAGE_SIZE = 100;
    private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    private ExecutionTrace() { }

    /** Initial untimed walking-skeleton evidence only; future producers need explicit contracts. */
    public enum Type {
        ATTEMPT_STARTED, FORM_CREATED, QUESTION_DELIVERED, ANSWER_SUBMITTED,
        ANSWER_LOCKED, ATTEMPT_SUBMITTED, AUTOMATIC_GRADE_PRODUCED, RESULT_RELEASED
    }
    public enum Actor { PARTICIPANT, SYSTEM }
    public enum ReasonCode { ACCEPTED, MATERIALIZED, DELIVERED, LOCKED, SUBMITTED, GRADED, RELEASED }

    /** Subject reference is the existing attempt-subject binding, never a copied user/contact ID. */
    public record Scope(UUID attemptId, UUID policySnapshotId, UUID formId) {
        public Scope { require(attemptId != null && policySnapshotId != null && formId != null, INVALID_INPUT); }
        @Override public String toString() { return "TraceScope[redacted]"; }
    }

    /** Optional metadata is fixed and typed; there is no content, string/map payload or extension bag. */
    public record Data(UUID commandId, UUID itemId, State from, State to, ReasonCode reason) {
        public Data { require((from == null) == (to == null) && reason != null, INVALID_INPUT); }
        @Override public String toString() { return "TraceData[redacted]"; }
    }

    /** Identity covers scope and all these semantic fields. Server time is assigned only once. */
    public record Event(int version, UUID id, Type type, Actor actor, Data data) {
        public Event {
            require(version == VERSION, UNSUPPORTED_VERSION);
            require(id != null && type != null && actor != null && data != null, INVALID_INPUT);
        }
        @Override public String toString() { return "TraceEvent[version=" + version + ",type=" + type + "]"; }
    }

    public record Stored(Scope scope, long sequence, Instant recordedAt, Event event) {
        public Stored {
            require(scope != null && sequence > 0 && event != null, INVALID_INPUT);
            require(recordedAt != null && !recordedAt.isBefore(MIN_TIME) && !recordedAt.isAfter(MAX_TIME), INVALID_INPUT);
            recordedAt = recordedAt.truncatedTo(ChronoUnit.MICROS);
        }
        @Override public String toString() { return "StoredTrace[sequence=" + sequence + ",event=" + event + "]"; }
    }

    public record Appended(Stored event, boolean replayed) {
        public Appended { require(event != null, INVALID_INPUT); }
    }

    public static <E extends Enum<E>> E parse(int version, Class<E> type, String value) {
        require(version == VERSION, UNSUPPORTED_VERSION);
        require(type != null && Set.of(Type.class, Actor.class, ReasonCode.class).contains(type), INVALID_INPUT);
        require(value != null && value.length() <= 32, UNKNOWN_VALUE);
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException failure) { throw new TraceException(UNKNOWN_VALUE); }
    }

    public static void page(long after, int limit) {
        require(after >= 0 && limit > 0 && limit <= MAX_PAGE_SIZE, INVALID_INPUT);
    }
}
