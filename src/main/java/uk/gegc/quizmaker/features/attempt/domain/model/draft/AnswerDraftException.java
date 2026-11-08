package uk.gegc.quizmaker.features.attempt.domain.model.draft;

/** Content-free storage failure; no SQL, response value or participant identifiers. */
public final class AnswerDraftException extends RuntimeException {
    public enum Reason { INVALID_INPUT, NOT_FOUND, CONFLICT, INACTIVE, UNSUPPORTED, CORRUPT, RETRYABLE, BUSY, UNAVAILABLE }
    private final Reason reason;

    public AnswerDraftException(Reason reason) {
        super("DRAFT_" + reason, null, false, true);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new AnswerDraftException(reason);
    }
}
