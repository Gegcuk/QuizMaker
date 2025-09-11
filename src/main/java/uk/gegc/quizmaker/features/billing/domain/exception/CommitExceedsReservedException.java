package uk.gegc.quizmaker.features.billing.domain.exception;

public class CommitExceedsReservedException extends RuntimeException {
    public CommitExceedsReservedException(String message) { super(message); }
}

