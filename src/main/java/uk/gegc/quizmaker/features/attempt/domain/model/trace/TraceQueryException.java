package uk.gegc.quizmaker.features.attempt.domain.model.trace;

/** No rejected values, SQL diagnostics, causes or suppressed exceptions cross the read boundary. */
public final class TraceQueryException extends RuntimeException {
    public enum Reason {
        INVALID_QUERY, INVALID_CURSOR, ACCESS_UNAVAILABLE, EVIDENCE_EXPIRED, EVIDENCE_RETAINED_AWAY,
        EVIDENCE_UNAVAILABLE, UNSUPPORTED_VERSION, CORRUPT_EVIDENCE, PERSISTENCE_UNAVAILABLE
    }
    private final Reason reason;

    public TraceQueryException(Reason reason) {
        super("Execution trace query rejected: " + reason);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) {
        if (!valid) throw new TraceQueryException(reason);
    }
}
