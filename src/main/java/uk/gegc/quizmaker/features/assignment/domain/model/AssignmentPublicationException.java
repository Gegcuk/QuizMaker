package uk.gegc.quizmaker.features.assignment.domain.model;

/** Bounded internal failure vocabulary; never retain source or SQL diagnostics. */
public final class AssignmentPublicationException extends RuntimeException {
    public enum Reason { INVALID_INPUT, UNAUTHENTICATED, UNAVAILABLE, STALE, CONFLICT, INVALID_REFERENCE, CORRUPT, UNSUPPORTED_TRANSACTION }
    private final Reason reason;
    public AssignmentPublicationException(Reason reason) { super("Assignment publication rejected: " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
    public static void require(boolean condition, Reason reason) { if (!condition) throw new AssignmentPublicationException(reason); }
}
