package uk.gegc.quizmaker.features.attempt.domain.model.selection;

/** Bounded internal failures; never echo identities, content or caller-supplied values. */
public final class QuestionSelectionException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, INVALID_CONFIGURATION, INVALID_RESULT, INVALID_ITEM,
        UNSUPPORTED_VERSION, UNSUPPORTED_REVISION_VERSION, UNSUPPORTED_STRATEGY_VERSION,
        UNKNOWN_STRATEGY, DUPLICATE_ITEM, DUPLICATE_POSITION, UNKNOWN_ITEM,
        INVALID_CONSTRAINT, CONSTRAINT_VIOLATION, REVISION_MISMATCH
    }

    private final Reason reason;

    public QuestionSelectionException(Reason reason) {
        super("Question selection rejected: " + java.util.Objects.requireNonNull(reason));
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    static void require(boolean valid, Reason reason) {
        if (!valid) throw new QuestionSelectionException(reason);
    }
}
