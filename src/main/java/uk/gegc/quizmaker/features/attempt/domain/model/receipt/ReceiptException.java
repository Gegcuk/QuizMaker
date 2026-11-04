package uk.gegc.quizmaker.features.attempt.domain.model.receipt;

import java.util.Objects;

/** Bounded internal failure. HTTP consumers map conflicts to 409 without exposing stored scope. */
public final class ReceiptException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT, UNSUPPORTED_VERSION, ACCESS_DENIED, COMMAND_DENIED, STALE_VERSION,
        CONFLICT, IN_PROGRESS, RETRYABLE, EXPIRED, CONTEXT_UNAVAILABLE, CORRUPT_RECEIPT,
        TRANSACTION_REQUIRED, PERSISTENCE_UNAVAILABLE, UNKNOWN_OUTCOME
    }
    private final Reason reason;

    public ReceiptException(Reason reason) {
        super("Command receipt rejected: " + Objects.requireNonNull(reason));
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new ReceiptException(reason);
    }
}
