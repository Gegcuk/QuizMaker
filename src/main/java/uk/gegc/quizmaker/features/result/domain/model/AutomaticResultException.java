package uk.gegc.quizmaker.features.result.domain.model;

/** Bounded, content-free failure. Unknown commit outcomes require the same submission ID. */
public final class AutomaticResultException extends RuntimeException {
    public enum Reason { INVALID_INPUT, NOT_FOUND, UNSUPPORTED, CORRUPT, BUSY, RETRYABLE, UNAVAILABLE }
    private final Reason reason;
    public AutomaticResultException(Reason reason) { super("Automatic result: " + reason,null,false,true); this.reason=reason; }
    public Reason reason() { return reason; }
    public static void require(boolean valid, Reason reason) { if(!valid) throw new AutomaticResultException(reason); }
}
