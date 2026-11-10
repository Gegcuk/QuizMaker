package uk.gegc.quizmaker.features.attempt.domain.model.timing;

import java.util.Objects;

/** Bounded internal failure. No SQL, resource identity or supplied payload is retained. */
public final class AttemptTimingException extends RuntimeException {
    public enum Reason { INVALID, CLOSED, MISSING, CORRUPT, UNSUPPORTED, UNAVAILABLE }
    private final Reason reason;
    public AttemptTimingException(Reason reason) {
        super("Attempt timing rejected: " + Objects.requireNonNull(reason)); this.reason = reason;
    }
    public Reason reason() { return reason; }
    public static void require(boolean condition, Reason reason) { if (!condition) throw new AttemptTimingException(reason); }
}
