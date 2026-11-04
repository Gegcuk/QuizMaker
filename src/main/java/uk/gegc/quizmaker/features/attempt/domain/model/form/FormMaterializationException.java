package uk.gegc.quizmaker.features.attempt.domain.model.form;

/** Bounded internal failures; no rejected values, source payload or underlying cause. */
public final class FormMaterializationException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, INPUT_MISMATCH, REVISION_UNAVAILABLE, INVALID_MATERIAL,
        UNSUPPORTED_FAMILY, UNSUPPORTED_MEDIA, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public FormMaterializationException(Reason reason) {
        super("Form materialization rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void require(boolean valid, Reason reason) {
        if (!valid) throw new FormMaterializationException(reason);
    }
}
