package uk.gegc.quizmaker.features.learning.domain.model;

/** Bounded internal failures; never retain input, library diagnostics or learner identifiers. */
public final class LearningScheduleException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNSUPPORTED_MANIFEST, INVALID_STATE,
        WRONG_SCHEDULE, STALE_STATE, INVALID_TIME, CALCULATION_FAILED, INVALID_FORMAT,
        UNAUTHENTICATED, UNAVAILABLE, SOURCE_UNAVAILABLE, CONFLICT, CORRUPT,
        ACTIVE_SESSION, SESSION_UNAVAILABLE, SESSION_INCOMPLETE, SESSION_CLOSED,
        UNSUPPORTED_TRANSACTION, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public LearningScheduleException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new LearningScheduleException(reason);
    }
}
