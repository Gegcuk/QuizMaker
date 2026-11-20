package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

/** Fixed, content-free internal failures. HTTP activation owns any future status mapping. */
public final class PublicationException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNAUTHENTICATED, UNAVAILABLE,
        INVALID_CONTENT, IMAGE_UNAVAILABLE, COMMAND_CONFLICT, STALE_PUBLICATION,
        TRANSACTION_NOT_ALLOWED, PERSISTENCE_UNAVAILABLE, UNKNOWN_OUTCOME
    }
    private final Reason reason;
    public PublicationException(Reason reason) { this(reason, null); }

    private PublicationException(Reason reason, DatabaseConflictException conflict) {
        super(reason.name(), conflict, false, true);
        this.reason = reason;
    }

    public static PublicationException persistenceUnavailable(RuntimeException failure) {
        return new PublicationException(Reason.PERSISTENCE_UNAVAILABLE, DatabaseConflictException.from(failure).orElse(null));
    }
    public Reason reason() { return reason; }
    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new PublicationException(reason);
    }
}
