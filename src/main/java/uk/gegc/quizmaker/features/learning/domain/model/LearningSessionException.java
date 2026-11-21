package uk.gegc.quizmaker.features.learning.domain.model;

/** Bounded internal failures: no principal, source payload or persistence diagnostic is retained. */
public final class LearningSessionException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNAUTHENTICATED, UNAVAILABLE,
        SOURCE_UNAVAILABLE, CONFLICT, STALE_REVIEW, REVIEW_COMPLETE, CORRUPT,
        UNSUPPORTED_TRANSACTION, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public LearningSessionException(Reason reason) {
        super(reason.name(), null, false, true);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new LearningSessionException(reason);
    }
}
