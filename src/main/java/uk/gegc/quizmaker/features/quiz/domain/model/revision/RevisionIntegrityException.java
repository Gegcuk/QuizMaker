package uk.gegc.quizmaker.features.quiz.domain.model.revision;

/** Bounded failure categories; messages never include stored question content. */
public final class RevisionIntegrityException extends IllegalArgumentException {
    public enum Reason {
        INVALID_CONTENT, UNSUPPORTED_VERSION, IDENTITY_CONFLICT, LEGACY_IDENTITY, INVALID_STORED_REVISION
    }

    private final Reason reason;

    public RevisionIntegrityException(Reason reason) {
        super("Immutable revision rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
