package uk.gegc.quizmaker.features.billing.domain.exception;

public class InvalidCheckoutSessionException extends RuntimeException {
    public InvalidCheckoutSessionException(String message) { super(message); }
}

