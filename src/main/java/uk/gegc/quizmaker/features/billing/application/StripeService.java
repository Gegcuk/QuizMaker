package uk.gegc.quizmaker.features.billing.application;

import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionResponse;
import uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse;
import uk.gegc.quizmaker.features.billing.api.dto.SubscriptionResponse;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;

import java.util.UUID;

/**
 * Stripe integration service for creating Checkout Sessions and managing customers.
 * Creates checkout sessions from server-resolved packs; no crediting occurs here.
 */
public interface StripeService {

    /**
     * Create a Stripe Checkout Session for one active, server-resolved token pack.
     *
     * @param userId   current authenticated user ID
     * @param pack     server-owned pack containing the coupled Stripe price and entitlement
     * @return session URL and ID
     */
    CheckoutSessionResponse createCheckoutSession(UUID userId, ProductPack pack);

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
     * Update a subscription that has already passed ownership validation without retrieving it again.
     *
     * @param subscription verified Stripe subscription
     * @param newPriceId server-resolved Stripe price identifier
     * @return updated subscription information
     */
    com.stripe.model.Subscription updateSubscription(
            com.stripe.model.Subscription subscription,
            String newPriceId
    ) throws com.stripe.exception.StripeException;

    /**
     * Cancel a subscription that has already passed ownership validation without retrieving it again.
     *
     * @param subscription verified Stripe subscription
     * @return cancelled subscription information
     */
    com.stripe.model.Subscription cancelSubscription(
            com.stripe.model.Subscription subscription
    ) throws com.stripe.exception.StripeException;

    /**
     * Retrieve a Stripe Subscription by ID.
     *
     * @param subscriptionId Stripe subscription ID
     * @return subscription information
     */
    com.stripe.model.Subscription retrieveSubscription(String subscriptionId) throws com.stripe.exception.StripeException;

    /**
     * Retrieve a Stripe Charge by ID.
     *
     * @param chargeId Stripe charge ID
     * @return charge information
     */
    com.stripe.model.Charge retrieveCharge(String chargeId) throws com.stripe.exception.StripeException;

    /**
     * Retrieve a raw Stripe Customer object by ID (for internal use).
     *
     * @param customerId Stripe customer ID
     * @return raw customer object
     */
    com.stripe.model.Customer retrieveCustomerRaw(String customerId) throws com.stripe.exception.StripeException;

    /**
     * Resolve a Stripe Price ID by its lookup key.
     *
     * @param lookupKey the Stripe price lookup key
     * @return the resolved Stripe Price ID
     */
    String resolvePriceIdByLookupKey(String lookupKey) throws com.stripe.exception.StripeException;
}
