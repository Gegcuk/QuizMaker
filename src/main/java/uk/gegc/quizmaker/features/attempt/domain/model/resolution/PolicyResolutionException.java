package uk.gegc.quizmaker.features.attempt.domain.model.resolution;

/** Stable internal failures; no input values, identifiers or private provenance in the message. */
public final class PolicyResolutionException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_RESOLVER_VERSION, UNSUPPORTED_CANONICAL_VERSION,
        UNSUPPORTED_HASH_ALGORITHM, UNSUPPORTED_SOURCE_VERSION, DUPLICATE_SOURCE,
        DUPLICATE_SETTING, FORBIDDEN_OVERRIDE, CANONICALIZATION_FAILED
    }

    private final Reason reason;

    public PolicyResolutionException(Reason reason) {
        super("Attempt policy resolution rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    static void require(boolean valid, Reason reason) {
        if (!valid) throw new PolicyResolutionException(reason);
    }
}
