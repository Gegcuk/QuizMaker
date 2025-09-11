package uk.gegc.quizmaker.features.billing.domain.exception;

public class InsufficientTokensException extends RuntimeException {
    public InsufficientTokensException(String message) { super(message); }
}

