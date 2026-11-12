package uk.gegc.quizmaker.features.assignment.domain.model;

/** Bounded diagnostics: never attach identifiers, roster contents or caller payloads. */
public final class AssignmentContractException extends IllegalArgumentException {
    public enum Reason { INVALID_IDENTITY, UNKNOWN_VERSION, INVALID_SCOPE, INVALID_AUDIENCE,
        INVALID_WINDOW, INVALID_CAPACITY, INVALID_GUEST_POLICY, INVALID_SNAPSHOT, UNKNOWN_POLICY }
    private final Reason reason;

    public AssignmentContractException(Reason reason) {
        super("Assignment contract rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    static void require(boolean condition, Reason reason) {
        if (!condition) throw new AssignmentContractException(reason);
    }
}
