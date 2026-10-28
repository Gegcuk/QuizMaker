package uk.gegc.quizmaker.features.attempt.domain.model.trace;

/** Bounded internal errors: never retain rejected values or database exceptions. */
public final class TraceException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNKNOWN_VALUE, CONTEXT_UNAVAILABLE,
        EVENT_CONFLICT, CORRUPT_TRACE, TRANSACTION_REQUIRED, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public TraceException(Reason reason) {
        super("Execution trace rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void require(boolean valid, Reason reason) {
        if (!valid) throw new TraceException(reason);
    }
}
