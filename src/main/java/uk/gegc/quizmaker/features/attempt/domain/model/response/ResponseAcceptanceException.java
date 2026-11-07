package uk.gegc.quizmaker.features.attempt.domain.model.response;

/** Bounded diagnostics only: never retain input, driver exceptions or protected content. */
public final class ResponseAcceptanceException extends RuntimeException {
    public enum Reason { UNAUTHENTICATED, NOT_FOUND, INVALID_INPUT, UNSUPPORTED, CONFLICT, STALE,
        INACTIVE, IN_PROGRESS, RETRYABLE, EXPIRED, UNKNOWN_OUTCOME, UNAVAILABLE, CORRUPT }
    private final Reason reason;
    public ResponseAcceptanceException(Reason reason) { super("Response acceptance rejected: " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) { if (!valid) throw new ResponseAcceptanceException(reason); }
}
