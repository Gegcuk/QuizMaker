package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.model.Charge;
import com.stripe.model.checkout.Session;
import com.stripe.model.Price;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import com.stripe.param.PriceListParams;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionResponse;
import uk.gegc.quizmaker.features.billing.api.dto.CustomerResponse;
import uk.gegc.quizmaker.features.billing.api.dto.SubscriptionResponse;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeCheckoutUnavailableException;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StripeServiceImpl implements StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeServiceImpl.class);

    private final StripeProperties stripeProperties;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StripeClient stripeClient;

    @Override
    public CheckoutSessionResponse createCheckoutSession(UUID userId, ProductPack pack) {
        if (pack == null || pack.getId() == null || !pack.isActive() || !StringUtils.hasText(pack.getStripePriceId())) {
            throw new IllegalArgumentException("An active server-resolved pack with a Stripe price is required");
        }
        String priceId = pack.getStripePriceId();

        String successUrl = stripeProperties.getSuccessUrl();
        String cancelUrl = stripeProperties.getCancelUrl();

        // Ensure success URL contains the placeholder for session reconciliation
        if (StringUtils.hasText(successUrl) && !successUrl.contains("{CHECKOUT_SESSION_ID}")) {
            successUrl = successUrl + (successUrl.contains("?") ? "&" : "?") + "session_id={CHECKOUT_SESSION_ID}";
        }

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(1L)
                                .setPrice(priceId)
                                .build()
                )
                .setClientReferenceId(userId.toString())
                .putMetadata("userId", userId.toString());

        params.putMetadata("packId", pack.getId().toString());
        params.putMetadata("priceId", priceId);

        try {
            Session session = (stripeClient != null)
                    ? stripeClient.checkout().sessions().create(params.build())
                    : Session.create(params.build());
            log.info("Created Stripe Checkout session id={} for user={} priceId={} packId={}",
                    session.getId(), userId, priceId, pack.getId());
            return new CheckoutSessionResponse(session.getUrl(), session.getId());
        } catch (StripeException e) {
            log.error("Failed to create Stripe Checkout session for user={} priceId={} packId={}: {}",
                    userId, priceId, pack.getId(), e.getMessage());
            throw new StripeCheckoutUnavailableException(e);
        }
    }

    @Override
    public Session retrieveSession(String sessionId, boolean expandLineItems) throws StripeException {
        if (stripeClient != null) {
            if (!expandLineItems) {
                return stripeClient.checkout().sessions().retrieve(sessionId);
            }
            SessionRetrieveParams params = SessionRetrieveParams.builder()
                    .addExpand("line_items")
                    .build();
            return stripeClient.checkout().sessions().retrieve(sessionId, params);
        } else {
            if (!expandLineItems) {
                return Session.retrieve(sessionId);
            }
            SessionRetrieveParams params = SessionRetrieveParams.builder()
                    .addExpand("line_items")
                    .build();
            return Session.retrieve(sessionId, params, null);
        }
    }

    @Override
    public CustomerResponse createCustomer(UUID userId, String email) throws StripeException {
        if (!StringUtils.hasText(email)) {
            throw new IllegalArgumentException("Email must be provided for customer creation");
        }

        CustomerCreateParams customerParams = CustomerCreateParams.builder()
                .setEmail(email)
                .putMetadata("userId", userId.toString())
                .build();

        Customer customer;
        if (stripeClient != null) {
            customer = stripeClient.customers().create(customerParams);
        } else {
            customer = Customer.create(customerParams);
        }

        log.info("Created Stripe customer id={} for user={} email={}", 
                customer.getId(), userId, email);

        return new CustomerResponse(
                customer.getId(),
                customer.getEmail(),
                customer.getName()
        );
    }

    @Override
    public CustomerResponse retrieveCustomer(String customerId) throws StripeException {
        if (!StringUtils.hasText(customerId)) {
            throw new IllegalArgumentException("Customer ID must be provided");
        }

        Customer customer;
        if (stripeClient != null) {
            customer = stripeClient.customers().retrieve(customerId);
        } else {
            customer = Customer.retrieve(customerId);
        }

        return new CustomerResponse(
                customer.getId(),
                customer.getEmail(),
                customer.getName()
        );
    }

    @Override
    public SubscriptionResponse createSubscription(String customerId, String priceId) throws StripeException {
        if (!StringUtils.hasText(customerId) || !StringUtils.hasText(priceId)) {
            throw new IllegalArgumentException("Customer ID and Price ID must be provided");
        }

        SubscriptionCreateParams subCreateParams = SubscriptionCreateParams.builder()
                .setCustomer(customerId)
                .addItem(
                        SubscriptionCreateParams.Item.builder()
                                .setPrice(priceId)
                                .build()
                )
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                .addAllExpand(java.util.Arrays.asList("latest_invoice.payment_intent"))
                .build();

        Subscription subscription;
        if (stripeClient != null) {
            subscription = stripeClient.subscriptions().create(subCreateParams);
        } else {
            subscription = Subscription.create(subCreateParams);
        }

        log.info("Created Stripe subscription id={} for customer={} priceId={}", 
                subscription.getId(), customerId, priceId);

        String clientSecret = subscription.getLatestInvoiceObject().getPaymentIntentObject().getClientSecret();
        return new SubscriptionResponse(subscription.getId(), clientSecret);
    }

    @Override
    public Subscription updateSubscription(Subscription subscription, String newPriceId) throws StripeException {
        return updateSubscriptionInternal(subscription, newPriceId, null);
    }

    @Override
    public Subscription updateSubscription(
            Subscription subscription,
            String newPriceId,
            String idempotencyKey
    ) throws StripeException {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Stripe idempotency key must be provided");
        }
        return updateSubscriptionInternal(subscription, newPriceId, idempotencyKey);
    }

    private Subscription updateSubscriptionInternal(
            Subscription subscription,
            String newPriceId,
            String idempotencyKey
    ) throws StripeException {
        if (subscription == null || !StringUtils.hasText(subscription.getId()) || !StringUtils.hasText(newPriceId)) {
            throw new IllegalArgumentException("Verified subscription and new Price ID must be provided");
        }
        if (subscription.getItems() == null || subscription.getItems().getData() == null
                || subscription.getItems().getData().isEmpty()) {
            throw new IllegalArgumentException("Verified subscription must contain at least one subscription item");
        }

        String subscriptionId = subscription.getId();

        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                .addItem(
                        SubscriptionUpdateParams.Item.builder()
                                .setId(subscription.getItems().getData().get(0).getId())
                                .setPrice(newPriceId)
                                .build()
                )
                .setCancelAtPeriodEnd(false)
                .build();

        RequestOptions requestOptions = requestOptions(idempotencyKey);
        Subscription updated;
        if (stripeClient != null) {
            updated = requestOptions == null
                    ? stripeClient.subscriptions().update(subscriptionId, params)
                    : stripeClient.subscriptions().update(subscriptionId, params, requestOptions);
        } else {
            updated = requestOptions == null
                    ? subscription.update(params)
                    : subscription.update(params, requestOptions);
        }

        log.info("Updated a verified Stripe subscription");
        return updated;
    }

    @Override
    public Subscription cancelSubscription(Subscription subscription) throws StripeException {
        return cancelSubscriptionInternal(subscription, null);
    }

    @Override
    public Subscription cancelSubscription(Subscription subscription, String idempotencyKey) throws StripeException {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Stripe idempotency key must be provided");
        }
        return cancelSubscriptionInternal(subscription, idempotencyKey);
    }

    private Subscription cancelSubscriptionInternal(
            Subscription subscription,
            String idempotencyKey
    ) throws StripeException {
        if (subscription == null || !StringUtils.hasText(subscription.getId())) {
            throw new IllegalArgumentException("Verified subscription must be provided");
        }

        String subscriptionId = subscription.getId();
        RequestOptions requestOptions = requestOptions(idempotencyKey);
        Subscription deletedSubscription;
        if (stripeClient != null) {
            deletedSubscription = requestOptions == null
                    ? stripeClient.subscriptions().cancel(subscriptionId)
                    : stripeClient.subscriptions().cancel(subscriptionId, requestOptions);
        } else {
            deletedSubscription = requestOptions == null
                    ? subscription.cancel()
                    : subscription.cancel(java.util.Map.of(), requestOptions);
        }

        log.info("Cancelled a verified Stripe subscription");
        return deletedSubscription;
    }

    private RequestOptions requestOptions(String idempotencyKey) {
        return StringUtils.hasText(idempotencyKey)
                ? RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()
                : null;
    }

    @Override
    public Subscription retrieveSubscription(String subscriptionId) throws StripeException {
        if (!StringUtils.hasText(subscriptionId)) {
            throw new IllegalArgumentException("Subscription ID must be provided");
        }

        Subscription subscription;
        if (stripeClient != null) {
            subscription = stripeClient.subscriptions().retrieve(subscriptionId);
        } else {
            subscription = Subscription.retrieve(subscriptionId);
        }

        return subscription;
    }

    @Override
    public Charge retrieveCharge(String chargeId) throws StripeException {
        if (!StringUtils.hasText(chargeId)) {
            throw new IllegalArgumentException("Charge ID must be provided");
        }

        Charge charge;
        if (stripeClient != null) {
            charge = stripeClient.charges().retrieve(chargeId);
        } else {
            charge = Charge.retrieve(chargeId);
        }

        return charge;
    }

    @Override
    public Customer retrieveCustomerRaw(String customerId) throws StripeException {
        if (!StringUtils.hasText(customerId)) {
            throw new IllegalArgumentException("Customer ID must be provided");
        }

        Customer customer;
        if (stripeClient != null) {
            customer = stripeClient.customers().retrieve(customerId);
        } else {
            customer = Customer.retrieve(customerId);
        }

        return customer;
    }

    @Override
    public String resolvePriceIdByLookupKey(String lookupKey) throws StripeException {
        if (!StringUtils.hasText(lookupKey)) {
            throw new IllegalArgumentException("Lookup key must be provided");
        }

        // List prices by lookup key and return the first active match
        PriceListParams params = PriceListParams.builder()
                .addLookupKey(lookupKey)
                .setActive(true)
                .setLimit(1L)
                .build();

        java.util.List<Price> prices;
        if (stripeClient != null) {
            prices = stripeClient.prices().list(params).getData();
        } else {
            prices = Price.list(params).getData();
        }

        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("No active price found for lookup key: " + lookupKey);
        }
        return prices.get(0).getId();
    }
}
