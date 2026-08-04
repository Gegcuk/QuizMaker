package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.StripeService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationService;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeSubscriptionUnavailableException;
import uk.gegc.quizmaker.features.billing.domain.exception.SubscriptionMutationConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionStatusRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Owns the authorization boundary for subscription mutations.
 *
 * <p>Remote Stripe calls intentionally run outside a database transaction. Holding a database transaction
 * while waiting on Stripe would retain database resources and still could not atomically commit the remote
 * mutation. The local subscription state is reconciled by the existing webhook flow.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionMutationServiceImpl implements SubscriptionMutationService {

    private static final String UPDATE = "update";
    private static final String CANCEL = "cancel";
    private static final String ALLOWED = "allowed";
    private static final String DENIED = "denied";
    private static final String FAILED = "failed";
    private static final String ALREADY_CANCELLED = "already_cancelled";

    private final SubscriptionStatusRepository subscriptionStatusRepository;
    private final StripeService stripeService;
    private final BillingMetricsService billingMetricsService;

    @Override
    public Subscription updateSubscription(UUID userId, String subscriptionId, String newPriceId) throws StripeException {
        Subscription subscription = loadOwnedSubscription(userId, subscriptionId, UPDATE);
        if (isCancelled(subscription)) {
            conflict(userId, subscriptionId, "cancelled_subscription");
        }

        try {
            Subscription updated = stripeService.updateSubscription(subscription, newPriceId);
            audit(userId, subscriptionId, UPDATE, ALLOWED, "updated");
            return updated;
        } catch (StripeException exception) {
            throw stripeFailure(userId, subscriptionId, UPDATE, exception);
        }
    }

    @Override
    public Subscription cancelSubscription(UUID userId, String subscriptionId) throws StripeException {
        Subscription subscription = loadOwnedSubscription(userId, subscriptionId, CANCEL);
        try {
            if (isCancelled(subscription)) {
                markLocallyCancelled(userId, subscriptionId);
                audit(userId, subscriptionId, CANCEL, ALLOWED, ALREADY_CANCELLED);
                return subscription;
            }

            Subscription cancelled = stripeService.cancelSubscription(subscription);
            markLocallyCancelled(userId, subscriptionId);
            audit(userId, subscriptionId, CANCEL, ALLOWED, "cancelled");
            return cancelled;
        } catch (StripeException exception) {
            throw stripeFailure(userId, subscriptionId, CANCEL, exception);
        } catch (ForbiddenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            audit(userId, subscriptionId, CANCEL, FAILED, "local_reconciliation_required");
            throw new StripeSubscriptionUnavailableException(exception);
        }
    }

    private Subscription loadOwnedSubscription(UUID userId, String requestedSubscriptionId, String operation)
            throws StripeException {
        if (userId == null || !StringUtils.hasText(requestedSubscriptionId)) {
            deny(userId, requestedSubscriptionId, operation, "invalid_request");
        }

        SubscriptionStatus localStatus = subscriptionStatusRepository.findByUserId(userId)
                .filter(status -> requestedSubscriptionId.equals(status.getSubscriptionId()))
                .orElseGet(() -> deny(userId, requestedSubscriptionId, operation, "local_mapping_mismatch"));

        List<SubscriptionStatus> matchingStatuses = subscriptionStatusRepository.findAllBySubscriptionId(requestedSubscriptionId);
        if (matchingStatuses.size() != 1 || !userId.equals(matchingStatuses.get(0).getUserId())
                || !localStatus.getId().equals(matchingStatuses.get(0).getId())) {
            deny(userId, requestedSubscriptionId, operation, "ambiguous_local_mapping");
        }

        Subscription subscription;
        try {
            subscription = stripeService.retrieveSubscription(requestedSubscriptionId);
        } catch (StripeException exception) {
            if (isNotFound(exception)) {
                deny(userId, requestedSubscriptionId, operation, "stale_subscription");
            }
            throw stripeFailure(userId, requestedSubscriptionId, operation, exception);
        }

        if (subscription == null || !requestedSubscriptionId.equals(subscription.getId())
                || !StringUtils.hasText(subscription.getCustomer())) {
            deny(userId, requestedSubscriptionId, operation, "invalid_stripe_subscription");
        }

        Customer customer;
        try {
            customer = stripeService.retrieveCustomerRaw(subscription.getCustomer());
        } catch (StripeException exception) {
            if (isNotFound(exception)) {
                deny(userId, requestedSubscriptionId, operation, "stale_customer");
            }
            throw stripeFailure(userId, requestedSubscriptionId, operation, exception);
        }

        String customerUserId = customer != null && customer.getMetadata() != null
                ? customer.getMetadata().get("userId")
                : null;
        if (customer == null || !subscription.getCustomer().equals(customer.getId())
                || !userId.toString().equals(customerUserId)) {
            deny(userId, requestedSubscriptionId, operation, "stripe_customer_mismatch");
        }

        return subscription;
    }

    private void markLocallyCancelled(UUID userId, String subscriptionId) {
        SubscriptionStatus status = subscriptionStatusRepository.findByUserId(userId)
                .filter(existing -> subscriptionId.equals(existing.getSubscriptionId()))
                .orElseGet(() -> deny(userId, subscriptionId, CANCEL, "local_mapping_changed"));
        if (status.isBlocked() && "subscription_cancelled_by_user".equals(status.getBlockReason())) {
            return;
        }
        status.setBlocked(true);
        status.setBlockReason("subscription_cancelled_by_user");
        subscriptionStatusRepository.save(status);
    }

    private StripeSubscriptionUnavailableException stripeFailure(
            UUID userId, String subscriptionId, String operation, StripeException exception) {
        audit(userId, subscriptionId, operation, FAILED, "stripe_unavailable");
        return new StripeSubscriptionUnavailableException(exception);
    }

    private boolean isNotFound(StripeException exception) {
        return Integer.valueOf(404).equals(exception.getStatusCode());
    }

    private boolean isCancelled(Subscription subscription) {
        return "canceled".equalsIgnoreCase(subscription.getStatus());
    }

    private SubscriptionStatus deny(UUID userId, String subscriptionId, String operation, String reason) {
        audit(userId, subscriptionId, operation, DENIED, reason);
        throw new ForbiddenException("Subscription mutation is not permitted");
    }

    private void conflict(UUID userId, String subscriptionId, String reason) {
        audit(userId, subscriptionId, UPDATE, FAILED, reason);
        throw new SubscriptionMutationConflictException("Subscription cannot be updated in its current state");
    }

    private void audit(UUID userId, String subscriptionId, String operation, String outcome, String reason) {
        billingMetricsService.recordSubscriptionMutation(operation, outcome, reason);
        log.info("billing_subscription_mutation operation={} outcome={} reason={} userHash={} subscriptionHash={}",
                operation, outcome, reason, shortHash(String.valueOf(userId)), shortHash(subscriptionId));
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
