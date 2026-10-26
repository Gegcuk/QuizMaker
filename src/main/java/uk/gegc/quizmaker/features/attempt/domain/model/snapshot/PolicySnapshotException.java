package uk.gegc.quizmaker.features.attempt.domain.model.snapshot;

/** Content-free failures; never retain JDBC/JSON exceptions or captured policy values. */
public final class PolicySnapshotException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, UNSUPPORTED_HASH_ALGORITHM, CORRUPT_SNAPSHOT,
        SNAPSHOT_CONFLICT, SNAPSHOT_UNAVAILABLE, PERSISTENCE_UNAVAILABLE
    }

    private final Reason reason;

    public PolicySnapshotException(Reason reason) {
        super("Policy snapshot operation rejected: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
