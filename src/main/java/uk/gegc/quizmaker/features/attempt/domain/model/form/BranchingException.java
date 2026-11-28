package uk.gegc.quizmaker.features.attempt.domain.model.form;

public final class BranchingException extends RuntimeException {
    public enum Reason { INVALID, UNAVAILABLE, DENIED, CORRUPT, UNSUPPORTED, STALE, CONFLICT, INACTIVE, RETRYABLE, UNKNOWN_OUTCOME }
    private final Reason reason;
    public BranchingException(Reason reason) {
        super("Scenario execution rejected: " + reason, null, false, false);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new BranchingException(reason);
    }
}
