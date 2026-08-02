package uk.gegc.quizmaker.features.billing.domain.exception;

public class StripeCheckoutUnavailableException extends RuntimeException {
    public StripeCheckoutUnavailableException(Throwable cause) {
        super("Stripe checkout is temporarily unavailable", cause);
    }
}
