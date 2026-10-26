package uk.gegc.quizmaker.features.attempt.domain.model.lifecycle;

/** Bounded internal reasons; never include caller input, responses or resource identities. */
public final class LifecycleException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNKNOWN_VALUE, UNSUPPORTED_VERSION, CONTRADICTORY_STATE,
        CONTRADICTORY_FACTS, MISSING_CONTEXT, ALREADY_STARTED, NOT_STARTED,
        TERMINAL_STATE, CONFLICTING_REPLAY, REPLAY_STATE_MISMATCH,
        INCOMPLETE_REQUIRED_SCOPE, CONFIRMATION_REQUIRED
    }

    private final Reason reason;

    public LifecycleException(Reason reason) {
        super("Attempt lifecycle rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    static void require(boolean condition, Reason reason) {
        if (!condition) throw new LifecycleException(reason);
    }
}
