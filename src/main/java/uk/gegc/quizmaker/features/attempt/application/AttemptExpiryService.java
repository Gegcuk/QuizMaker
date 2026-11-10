package uk.gegc.quizmaker.features.attempt.application;

import java.time.Instant;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

/** Internal system commands only; no participant identity, HTTP route or activation authority. */
public interface AttemptExpiryService {
    Result expire(UUID attemptId);
    Batch runBatch(int limit, Cursor after);
    enum Outcome { EXPIRED, REPLAYED, ALREADY_TERMINAL, NOT_DUE, BUSY }
    record Result(Outcome outcome, CommandReceipt.Acknowledgement acknowledgement) { }
    /** Keyset cursor is transient scheduling metadata, never a lease or command identity. */
    record Cursor(Instant deadline, UUID attemptId) {
        public Cursor { if (deadline == null || attemptId == null) throw new Failure(Failure.Reason.INVALID); }
        @Override public String toString() { return "ExpiryCursor[redacted]"; }
    }
    record Batch(int examined, int expired, int failed, Cursor next) { }
    final class Failure extends RuntimeException {
        public enum Reason { INVALID, UNSUPPORTED, CORRUPT, UNAVAILABLE }
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
