package uk.gegc.quizmaker.features.attempt.domain.model.access;

import java.util.Objects;

/** Non-enumerating internal failure. Future HTTP adapters own uniform ProblemDetail mapping. */
public final class AttemptAccessException extends RuntimeException {
    public enum Reason { UNAUTHENTICATED, DENIED, UNAVAILABLE, IN_PROGRESS, RETRYABLE }
    private final Reason reason;

    public AttemptAccessException(Reason reason) {
        super("Attempt access rejected: " + Objects.requireNonNull(reason), null, false, true);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
