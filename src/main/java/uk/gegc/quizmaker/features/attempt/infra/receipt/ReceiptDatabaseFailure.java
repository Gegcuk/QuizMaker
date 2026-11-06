package uk.gegc.quizmaker.features.attempt.infra.receipt;

import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

/** Classify before sanitizing; never retain a driver message, cause or SQL payload. */
public final class ReceiptDatabaseFailure {
    private ReceiptDatabaseFailure() { }

    public static ReceiptException.Reason reason(Throwable failure) {
        return DatabaseConflictException.from(failure).map(conflict -> switch (conflict.kind()) {
            case DEADLOCK -> ReceiptException.Reason.RETRYABLE;
            case LOCK_TIMEOUT -> ReceiptException.Reason.IN_PROGRESS;
        }).orElse(ReceiptException.Reason.PERSISTENCE_UNAVAILABLE);
    }
}
