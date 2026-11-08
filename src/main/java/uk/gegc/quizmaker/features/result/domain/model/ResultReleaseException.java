package uk.gegc.quizmaker.features.result.domain.model;

/** No provider, database, content or identity diagnostics cross the release boundary. */
public final class ResultReleaseException extends RuntimeException {
    public enum Reason { INVALID_INPUT, NOT_FOUND, NOT_READY, UNSUPPORTED, CORRUPT, BUSY, RETRYABLE, UNAVAILABLE }
    private final Reason reason;
    public ResultReleaseException(Reason reason) {
        super("Result release: " + reason, null, false, true);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) {
        if (!valid) throw new ResultReleaseException(reason);
    }
}
