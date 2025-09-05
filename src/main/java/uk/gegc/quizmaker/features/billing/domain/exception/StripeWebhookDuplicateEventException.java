package uk.gegc.quizmaker.features.billing.domain.exception;

public class StripeWebhookDuplicateEventException extends RuntimeException {
    public StripeWebhookDuplicateEventException(String message) { super(message); }
}

