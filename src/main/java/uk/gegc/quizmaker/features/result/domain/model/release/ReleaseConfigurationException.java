package uk.gegc.quizmaker.features.result.domain.model.release;

/** Bounded diagnostics: never retain parser, driver, principal or request content. */
public final class ReleaseConfigurationException extends RuntimeException {
    public enum Reason { INVALID_INPUT, UNSUPPORTED_VERSION, INVALID_SCHEDULE, UNAUTHENTICATED,
        NOT_FOUND, STALE, CONFLICT, CORRUPT, UNAVAILABLE, UNKNOWN_OUTCOME }
    private final Reason reason;
    public ReleaseConfigurationException(Reason reason) {
        super("Release configuration rejected: " + reason, null, false, false);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new ReleaseConfigurationException(reason);
    }
}
