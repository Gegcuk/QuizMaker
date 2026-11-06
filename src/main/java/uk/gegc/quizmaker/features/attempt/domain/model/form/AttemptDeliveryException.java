package uk.gegc.quizmaker.features.attempt.domain.model.form;

/** Sanitized before leaving the transaction, including causes and suppressed exceptions. */
public final class AttemptDeliveryException extends RuntimeException {
    public enum Reason { UNAUTHENTICATED, NOT_FOUND, INACTIVE, UNSUPPORTED, CORRUPT, BUSY, UNAVAILABLE }
    private final Reason reason;
    public AttemptDeliveryException(Reason reason) {
        super("Attempt delivery rejected: " + reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
