package uk.gegc.quizmaker.features.learning.domain.model;

/** Bounded failures; collaborator/driver diagnostics must not cross this boundary. */
public final class LearningFormException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNAVAILABLE, SOURCE_UNAVAILABLE, CONFLICT,
        CORRUPT, UNSUPPORTED_TRANSACTION, PERSISTENCE_UNAVAILABLE
    }
    private final Reason reason;
    public LearningFormException(Reason reason) {
        super(reason.name(), null, false, true);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new LearningFormException(reason);
    }
}
