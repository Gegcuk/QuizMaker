package uk.gegc.quizmaker.features.billing.domain.exception;

/**
 * Raised when an authenticated user requests a mutation that conflicts with the subscription's current state.
 */
public class SubscriptionMutationConflictException extends RuntimeException {

    public SubscriptionMutationConflictException(String message) {
        super(message);
    }
}
