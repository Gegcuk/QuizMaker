package uk.gegc.quizmaker.features.billing.domain.exception;

/**
 * Raised when Stripe cannot safely complete a subscription mutation. The caller can retry later.
 */
public class StripeSubscriptionUnavailableException extends RuntimeException {

    public StripeSubscriptionUnavailableException(Throwable cause) {
        super("Subscription service is temporarily unavailable", cause);
    }
}
