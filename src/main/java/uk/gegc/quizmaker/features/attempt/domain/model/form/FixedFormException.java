package uk.gegc.quizmaker.features.attempt.domain.model.form;

/** Stable internal failures without identifiers, content or database diagnostics. */
public final class FixedFormException extends RuntimeException {
    public enum Reason {
        INVALID_FORM, UNSUPPORTED_VERSION, REVISION_UNAVAILABLE, FORM_UNAVAILABLE,
        FORM_CONFLICT, INVALID_STORED_FORM, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public FixedFormException(Reason reason) {
        super("Fixed form rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
