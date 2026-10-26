package uk.gegc.quizmaker.features.attempt.domain.model.subject;

/** Non-enumerating failure. Reasons are bounded internal telemetry, not public error contracts. */
public final class SubjectResolutionException extends RuntimeException {
    public enum Reason { UNAUTHENTICATED, INVALID, MIXED, UNAVAILABLE, INVALID_ATTEMPT }

    private final Reason reason;

    public SubjectResolutionException(Reason reason) {
        super("Authenticated participant unavailable");
        this.reason = java.util.Objects.requireNonNull(reason);
    }

    public Reason reason() { return reason; }
}
