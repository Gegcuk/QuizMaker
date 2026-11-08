package uk.gegc.quizmaker.features.attempt.domain.model.draft;

/** Fixed reasons only: never retain answers, request keys, driver messages or callback causes. */
public final class DraftCommandException extends RuntimeException {
    public enum Reason {
        UNAUTHENTICATED, NOT_FOUND, INVALID_INPUT, UNSUPPORTED, CONFLICT, STALE, INACTIVE,
        IN_PROGRESS, EXPIRED, CORRUPT, RETRYABLE, UNKNOWN_OUTCOME, UNAVAILABLE
    }
    private final Reason reason;
    public DraftCommandException(Reason reason) {
        super("DRAFT_COMMAND_" + reason.name(), null, false, false);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) { if (!valid) throw new DraftCommandException(reason); }
}
