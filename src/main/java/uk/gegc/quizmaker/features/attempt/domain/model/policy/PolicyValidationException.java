package uk.gegc.quizmaker.features.attempt.domain.model.policy;

/** Stable internal failures without policy payloads, identities or protected content. */
public final class PolicyValidationException extends RuntimeException {
    public enum Reason {
        INVALID_VALUE, UNKNOWN_VALUE, UNSUPPORTED_SCHEMA_VERSION, UNSUPPORTED_CONTENT_VERSION,
        UNSUPPORTED_STRATEGY_VERSION, INVALID_COMBINATION, UNSUPPORTED_COMBINATION, UNSUPPORTED_CAPABILITY
    }

    private final Reason reason;

    public PolicyValidationException(Reason reason) {
        super("Attempt policy rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
