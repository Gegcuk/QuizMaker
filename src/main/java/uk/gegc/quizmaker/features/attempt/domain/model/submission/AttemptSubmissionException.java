package uk.gegc.quizmaker.features.attempt.domain.model.submission;

import java.util.List;
import java.util.UUID;

/** Bounded reasons and already-authorized form item identities only. */
public final class AttemptSubmissionException extends RuntimeException {
    public enum Reason { UNAUTHENTICATED, NOT_FOUND, INVALID_INPUT, UNSUPPORTED, CONFLICT, STALE,
        INACTIVE, IN_PROGRESS, RETRYABLE, EXPIRED, UNKNOWN_OUTCOME, UNAVAILABLE, CORRUPT,
        INCOMPLETE, CONFIRMATION_REQUIRED }
    private final Reason reason;
    private final List<UUID> missing;
    public AttemptSubmissionException(Reason reason) { this(reason, List.of()); }
    public AttemptSubmissionException(Reason reason, List<UUID> missing) {
        super("Attempt submission rejected: " + reason);
        this.reason = reason;
        this.missing = reason == Reason.INCOMPLETE ? List.copyOf(missing) : List.of();
    }
    public Reason reason() { return reason; }
    public List<UUID> missing() { return missing; }
    public static void require(boolean valid, Reason reason) { if (!valid) throw new AttemptSubmissionException(reason); }
}
