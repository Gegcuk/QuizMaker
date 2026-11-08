package uk.gegc.quizmaker.features.attempt.domain.model.navigation;

/** Bounded diagnostics only; never retain request keys, private content or driver causes. */
public final class NavigationException extends RuntimeException {
    public enum Reason {
        UNAUTHENTICATED, NOT_FOUND, INVALID_INPUT, UNSUPPORTED, CONFLICT, STALE, INACTIVE,
        IN_PROGRESS, EXPIRED, CORRUPT, RETRYABLE, UNKNOWN_OUTCOME, UNAVAILABLE,
        ANSWER_REQUIRED, TRANSITION_DENIED
    }
    private final Reason reason;
    public NavigationException(Reason reason) {
        super("NAVIGATION_" + reason.name(), null, false, false); this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) { if (!valid) throw new NavigationException(reason); }
}
