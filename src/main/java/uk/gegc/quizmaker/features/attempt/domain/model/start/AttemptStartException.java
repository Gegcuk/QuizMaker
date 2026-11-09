package uk.gegc.quizmaker.features.attempt.domain.model.start;

import java.util.Objects;

/** Content-free internal failure; a future HTTP adapter owns status and ProblemDetail mapping. */
public final class AttemptStartException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNAUTHENTICATED, ACCESS_DENIED, STALE_PUBLICATION,
        UNSUPPORTED_CAPABILITY, UNSUPPORTED_PRESET, UNSUPPORTED_LEGACY_MODE,
        CONTRACT_UNAVAILABLE, MATERIAL_UNAVAILABLE, SELECTION_REJECTED, SELECTION_UNAVAILABLE, CONFLICT, IN_PROGRESS, RETRYABLE, EXPIRED,
        PERSISTENCE_UNAVAILABLE, UNKNOWN_OUTCOME, ACTIVE_ATTEMPT, LIMIT_EXHAUSTED, COOLDOWN, ENTRY_CLOSED,
        ALLOWANCE_CONFLICT, ELIGIBILITY_UNAVAILABLE
    }
    private final Reason reason;
    private final java.time.Instant nextEligibleAt;
    public AttemptStartException(Reason reason) { this(reason, null); }
    public AttemptStartException(Reason reason, java.time.Instant nextEligibleAt) {
        super("Attempt start rejected: " + Objects.requireNonNull(reason));
        this.reason = reason;
        this.nextEligibleAt = reason == Reason.COOLDOWN ? nextEligibleAt : null;
    }
    public java.time.Instant nextEligibleAt() { return nextEligibleAt; }
    public Reason reason() { return reason; }
    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new AttemptStartException(reason);
    }
}
