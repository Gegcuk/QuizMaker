package uk.gegc.quizmaker.features.billing.domain.exception;

public class StripeWebhookInvalidSignatureException extends RuntimeException {
    public StripeWebhookInvalidSignatureException(String message) { super(message); }
}

