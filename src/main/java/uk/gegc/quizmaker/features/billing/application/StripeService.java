package uk.gegc.quizmaker.features.billing.application;

import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionResponse;
import uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse;
import uk.gegc.quizmaker.features.billing.api.dto.SubscriptionResponse;

import java.util.UUID;

/**
 * Stripe integration service for creating Checkout Sessions and managing customers.
 * Part A scope: create session with priceId and metadata; no crediting here.
 */
public interface StripeService {

    /**
     * Create a Stripe Checkout Session for purchasing a token pack.
     * Either {@code priceId} (Stripe Price ID) or {@code packId} may be provided to carry metadata.
     *
     * @param userId   current authenticated user ID
     * @param priceId  Stripe Price ID (required for MVP if not resolving via pack)
     * @param packId   Optional internal ProductPack ID for metadata
     * @return session URL and ID
     */
    CheckoutSessionResponse createCheckoutSession(UUID userId, String priceId, UUID packId);

    /** Retrieve a Checkout Session by id, optionally expanding line_items for pack resolution. */
    com.stripe.model.checkout.Session retrieveSession(String sessionId, boolean expandLineItems) throws com.stripe.exception.StripeException;

    /**
     * Create a Stripe Customer for the given user.
     *
     * @param userId current authenticated user ID
     * @param email  customer email address
     * @return customer information
     */
    CustomerResponse createCustomer(UUID userId, String email) throws com.stripe.exception.StripeException;

    /**
     * Retrieve a Stripe Customer by ID.
     *
     * @param customerId Stripe customer ID
     * @return customer information
     */
    CustomerResponse retrieveCustomer(String customerId) throws com.stripe.exception.StripeException;

    /**
     * Create a Stripe Subscription for the given customer and price.
     *
     * @param customerId Stripe customer ID
     * @param priceId    Stripe price ID
     * @return subscription information with client secret
     */
    SubscriptionResponse createSubscription(String customerId, String priceId) throws com.stripe.exception.StripeException;

    /**
     * Update a Stripe Subscription to a new price.
     *
     * @param subscriptionId    Stripe subscription ID
     * @param newPriceId        New Stripe price ID
     * @return updated subscription information
     */
    com.stripe.model.Subscription updateSubscription(String subscriptionId, String newPriceId) throws com.stripe.exception.StripeException;

    /**
     * Cancel a Stripe Subscription.
     *
     * @param subscriptionId Stripe subscription ID
     * @return cancelled subscription information
     */
    com.stripe.model.Subscription cancelSubscription(String subscriptionId) throws com.stripe.exception.StripeException;
}
