package uk.gegc.quizmaker.shared.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Exception thrown when trying to access attempt review or answer key
 * for an attempt that has not been completed yet.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class AttemptNotCompletedException extends RuntimeException {

    private final UUID attemptId;

    public AttemptNotCompletedException(UUID attemptId) {
        super("Attempt " + attemptId + " is not completed yet. Review is only available for completed attempts.");
        this.attemptId = attemptId;
    }

    public AttemptNotCompletedException(UUID attemptId, String customMessage) {
        super(customMessage);
        this.attemptId = attemptId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }
}

