package uk.gegc.quizmaker.features.attempt.domain.model.lifecycle;

/** Bounded internal storage failures; never include resource identities or database details. */
public final class LifecycleStateException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, INVALID_TRANSITION, UNSUPPORTED_VERSION, CORRUPT_STATE,
        STATE_UNAVAILABLE, STATE_CONFLICT, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public LifecycleStateException(Reason reason) {
        super("Lifecycle state rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    static void require(boolean condition, Reason reason) {
        if (!condition) throw new LifecycleStateException(reason);
    }
}
