package uk.gegc.quizmaker.features.attempt.domain.model.form;

import java.util.Objects;

/** Bounded internal failures; never retain identifiers, content or persistence diagnostics. */
public final class ProgressiveFormAppendException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNSUPPORTED_REVISION_VERSION, FIXED_FORM,
        REFERENCE_MISMATCH, INVALID_STATE, INVALID_ITEM, DUPLICATE_ITEM, INVALID_ORDER,
        UNKNOWN_SOURCE_ITEM, STALE_VERSION, DECISION_CONFLICT, INVALID_RECEIPT,
        PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public ProgressiveFormAppendException(Reason reason) {
        super("Progressive form append rejected: " + Objects.requireNonNull(reason));
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    static void require(boolean valid, Reason reason) {
        if (!valid) throw new ProgressiveFormAppendException(reason);
    }
}
