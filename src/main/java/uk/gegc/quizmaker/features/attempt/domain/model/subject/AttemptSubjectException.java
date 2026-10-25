package uk.gegc.quizmaker.features.attempt.domain.model.subject;

/** Bounded internal failures: no identity values or database diagnostics in the message. */
public final class AttemptSubjectException extends RuntimeException {
    public enum Reason {
        INVALID_SUBJECT, UNSUPPORTED_VERSION, UNSUPPORTED_TYPE, SUBJECT_UNAVAILABLE,
        SUBJECT_CONFLICT, INVALID_STORED_SUBJECT, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public AttemptSubjectException(Reason reason) {
        super("Attempt subject rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
