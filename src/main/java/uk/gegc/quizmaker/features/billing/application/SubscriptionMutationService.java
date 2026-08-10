package uk.gegc.quizmaker.features.billing.application;

import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;

import java.util.UUID;

/**
 * Authorizes and performs subscription changes requested by an authenticated user.
 */
public interface SubscriptionMutationService {

    /**
     * Changes the price of the user's recorded Stripe subscription.
     *
     * @param userId authenticated local user
     * @param subscriptionId untrusted client-supplied identifier that must match the local record
     * @param newPriceId server-resolved Stripe price identifier
     * @return the updated Stripe subscription
     */
    Subscription updateSubscription(UUID userId, String subscriptionId, String newPriceId) throws StripeException;

    /**
     * Changes the subscription price with an optional client retry key.
     *
     * @param idempotencyKey optional client key; exact retries replay and changed payloads conflict
     */
    default Subscription updateSubscription(
            UUID userId,
            String subscriptionId,
            String newPriceId,
            String idempotencyKey
    ) throws StripeException {
        return updateSubscription(userId, subscriptionId, newPriceId);
    }

    /**
     * Cancels the user's recorded Stripe subscription. Repeating a completed cancellation is idempotent.
     *
     * @param userId authenticated local user
     * @param subscriptionId untrusted client-supplied identifier that must match the local record
     * @return the cancelled Stripe subscription
     */
    Subscription cancelSubscription(UUID userId, String subscriptionId) throws StripeException;

    /**
     * Cancels the subscription with an optional client retry key.
     *
     * @param idempotencyKey optional client key; exact retries replay and changed payloads conflict
     */
    default Subscription cancelSubscription(
            UUID userId,
            String subscriptionId,
            String idempotencyKey
    ) throws StripeException {
        return cancelSubscription(userId, subscriptionId);
    }
}
