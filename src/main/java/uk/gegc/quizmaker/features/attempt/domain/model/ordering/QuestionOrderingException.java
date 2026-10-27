package uk.gegc.quizmaker.features.attempt.domain.model.ordering;

/** Bounded internal reasons; never echo identities, content or caller-supplied values. */
public final class QuestionOrderingException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNSUPPORTED_REVISION_VERSION,
        INVALID_ITEM, DUPLICATE_ITEM, DUPLICATE_POSITION, UNKNOWN_ITEM, REVISION_MISMATCH
    }

    private final Reason reason;

    public QuestionOrderingException(Reason reason) {
        super("Question ordering rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    static void require(boolean condition, Reason reason) {
        if (!condition) throw new QuestionOrderingException(reason);
    }
}
