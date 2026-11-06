package uk.gegc.quizmaker.features.attempt.infra.receipt;

import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Classify before sanitizing; never retain a driver message, cause or SQL payload. */
public final class ReceiptDatabaseFailure {
    private ReceiptDatabaseFailure() { }

    public static ReceiptException.Reason reason(Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == 1213) return ReceiptException.Reason.RETRYABLE;
                if (sql.getErrorCode() == 1205) return ReceiptException.Reason.IN_PROGRESS;
            }
        }
        return ReceiptException.Reason.PERSISTENCE_UNAVAILABLE;
    }
}
