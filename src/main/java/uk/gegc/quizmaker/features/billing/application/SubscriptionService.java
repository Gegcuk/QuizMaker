package uk.gegc.quizmaker.features.billing.application;

import java.util.UUID;

/**
 * Service for managing subscription lifecycle and policies.
 */
public interface SubscriptionService {

    /**
     * Get the number of tokens to credit for a subscription billing period.
     * 
     * @param subscriptionId the Stripe subscription ID
     * @param priceId the Stripe price ID
     * @return number of tokens to credit
     */
    long getTokensPerPeriod(String subscriptionId, String priceId);

    /**
     * Handle subscription payment success.
     * 
     * @param userId the user ID
     * @param subscriptionId the Stripe subscription ID
     * @param periodStart the billing period start timestamp
     * @param tokensPerPeriod number of tokens to credit
     * @param eventId the webhook event ID for idempotency
     * @return true if tokens were credited, false if already processed
     */
    boolean handleSubscriptionPaymentSuccess(UUID userId, String subscriptionId, long periodStart, 
                                           long tokensPerPeriod, String eventId);

    /**
     * Handle subscription payment failure.
     * 
     * @param userId the user ID
     * @param subscriptionId the Stripe subscription ID
     * @param reason the failure reason
     */
    void handleSubscriptionPaymentFailure(UUID userId, String subscriptionId, String reason);

    /**
     * Handle subscription deletion/cancellation.
     * 
     * @param userId the user ID
     * @param subscriptionId the Stripe subscription ID
     * @param reason the cancellation reason
     */
    void handleSubscriptionDeleted(UUID userId, String subscriptionId, String reason);

    /**
     * Check if a user's subscription is active (not blocked).
     * 
     * @param userId the user ID
     * @return true if subscription is active, false if blocked
     */
    boolean isSubscriptionActive(UUID userId);

    /**
     * Block a user's subscription (soft lock).
     * 
     * @param userId the user ID
     * @param reason the blocking reason
     */
    void blockSubscription(UUID userId, String reason);

    /**
     * Unblock a user's subscription.
     * 
     * @param userId the user ID
     */
    void unblockSubscription(UUID userId);
}
