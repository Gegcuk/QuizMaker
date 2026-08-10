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
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationClaim;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationCoordinator;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationService;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeSubscriptionUnavailableException;
import uk.gegc.quizmaker.features.billing.domain.exception.SubscriptionMutationConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationState;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationType;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionStatusRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Owns the authorization boundary for subscription mutations.
 *
 * <p>Remote Stripe calls intentionally run outside a database transaction. A durable operation is claimed in
 * a short transaction first, and its stable provider key makes crash and timeout retries safe. Provider state,
 * the durable operation, and the existing webhook flow reconcile local state after partial failures.</p>
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
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final long WAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long WAIT_POLL_MILLIS = 25L;

    private final SubscriptionStatusRepository subscriptionStatusRepository;
    private final StripeService stripeService;
    private final BillingMetricsService billingMetricsService;
    private final SubscriptionMutationCoordinator mutationCoordinator;

    @Override
    public Subscription updateSubscription(UUID userId, String subscriptionId, String newPriceId) throws StripeException {
        return updateSubscription(userId, subscriptionId, newPriceId, null);
    }

    @Override
    public Subscription updateSubscription(
            UUID userId,
            String subscriptionId,
            String newPriceId,
            String idempotencyKey
    ) throws StripeException {
        validateIdempotencyKey(idempotencyKey);

        while (true) {
            Subscription subscription = loadOwnedSubscription(userId, subscriptionId, UPDATE);
            if (isCancelled(subscription)) {
                conflict(userId, subscriptionId, "cancelled_subscription");
            }

            SubscriptionMutationClaim claim = claim(
                    userId,
                    subscriptionId,
                    SubscriptionMutationType.UPDATE,
                    newPriceId,
                    idempotencyKey,
                    hasPrice(subscription, newPriceId),
                    UPDATE
            );
            if (claim.action() == SubscriptionMutationClaim.Action.REPLAY) {
                audit(userId, subscriptionId, UPDATE, ALLOWED, "already_updated");
                return subscription;
            }
            if (claim.action() == SubscriptionMutationClaim.Action.WAIT) {
                awaitOperation(userId, subscriptionId, claim, UPDATE);
                continue;
            }

            Subscription updated;
            try {
                updated = stripeService.updateSubscription(
                        subscription, newPriceId, claim.stripeIdempotencyKey());
            } catch (StripeException exception) {
                makeRetryableSafely(claim, userId);
                throw stripeFailure(userId, subscriptionId, UPDATE, exception);
            } catch (RuntimeException exception) {
                makeRetryableSafely(claim, userId);
                audit(userId, subscriptionId, UPDATE, FAILED, "stripe_unavailable");
                throw new StripeSubscriptionUnavailableException(exception);
            }

            completeAfterRemoteMutation(claim, userId, subscriptionId, UPDATE);
            audit(userId, subscriptionId, UPDATE, ALLOWED, "updated");
            return updated;
        }
    }

    @Override
    public Subscription cancelSubscription(UUID userId, String subscriptionId) throws StripeException {
        return cancelSubscription(userId, subscriptionId, null);
    }

    @Override
    public Subscription cancelSubscription(
            UUID userId,
            String subscriptionId,
            String idempotencyKey
    ) throws StripeException {
        validateIdempotencyKey(idempotencyKey);

        while (true) {
            Subscription subscription = loadOwnedSubscription(userId, subscriptionId, CANCEL);
            SubscriptionMutationClaim claim = claim(
                    userId,
                    subscriptionId,
                    SubscriptionMutationType.CANCEL,
                    null,
                    idempotencyKey,
                    isCancelled(subscription),
                    CANCEL
            );
            if (claim.action() == SubscriptionMutationClaim.Action.REPLAY) {
                audit(userId, subscriptionId, CANCEL, ALLOWED, ALREADY_CANCELLED);
                return subscription;
            }
            if (claim.action() == SubscriptionMutationClaim.Action.WAIT) {
                awaitOperation(userId, subscriptionId, claim, CANCEL);
                continue;
            }

            Subscription cancelled;
            try {
                cancelled = stripeService.cancelSubscription(subscription, claim.stripeIdempotencyKey());
            } catch (StripeException exception) {
                makeRetryableSafely(claim, userId);
                throw stripeFailure(userId, subscriptionId, CANCEL, exception);
            } catch (RuntimeException exception) {
                makeRetryableSafely(claim, userId);
                audit(userId, subscriptionId, CANCEL, FAILED, "stripe_unavailable");
                throw new StripeSubscriptionUnavailableException(exception);
            }

            completeAfterRemoteMutation(claim, userId, subscriptionId, CANCEL);
            audit(userId, subscriptionId, CANCEL, ALLOWED, "cancelled");
            return cancelled;
        }
    }

    private SubscriptionMutationClaim claim(
            UUID userId,
            String subscriptionId,
            SubscriptionMutationType operationType,
            String targetPriceId,
            String idempotencyKey,
            boolean remoteAlreadyApplied,
            String auditOperation
    ) {
        try {
            return mutationCoordinator.claim(
                    userId,
                    subscriptionId,
                    operationType,
                    targetPriceId,
                    idempotencyKey,
                    remoteAlreadyApplied
            );
        } catch (ForbiddenException exception) {
            audit(userId, subscriptionId, auditOperation, DENIED, "local_mapping_changed");
            throw exception;
        }
    }

    private void completeAfterRemoteMutation(
            SubscriptionMutationClaim claim,
            UUID userId,
            String subscriptionId,
            String operation
    ) {
        try {
            mutationCoordinator.complete(claim.operationId(), userId);
        } catch (ForbiddenException exception) {
            audit(userId, subscriptionId, operation, DENIED, "local_mapping_changed");
            throw exception;
        } catch (RuntimeException exception) {
            audit(userId, subscriptionId, operation, FAILED, "local_reconciliation_required");
            throw new StripeSubscriptionUnavailableException(exception);
        }
    }

    private void awaitOperation(
            UUID userId,
            String subscriptionId,
            SubscriptionMutationClaim claim,
            String operation
    ) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            SubscriptionMutationState state = mutationCoordinator
                    .getState(claim.operationId(), userId)
                    .orElse(SubscriptionMutationState.RETRYABLE);
            if (state != SubscriptionMutationState.IN_PROGRESS) {
                return;
            }
            try {
                Thread.sleep(WAIT_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                audit(userId, subscriptionId, operation, FAILED, "concurrent_wait_interrupted");
                throw new StripeSubscriptionUnavailableException(exception);
            }
        }
        audit(userId, subscriptionId, operation, FAILED, "concurrent_wait_timeout");
        throw new StripeSubscriptionUnavailableException(
                new IllegalStateException("A subscription mutation is still in progress"));
    }

    private void makeRetryableSafely(SubscriptionMutationClaim claim, UUID userId) {
        try {
            mutationCoordinator.makeRetryable(claim.operationId(), userId);
        } catch (RuntimeException exception) {
            log.warn("Could not release a failed subscription mutation claim for retry", exception);
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

    private boolean hasPrice(Subscription subscription, String priceId) {
        return subscription.getItems() != null
                && subscription.getItems().getData() != null
                && !subscription.getItems().getData().isEmpty()
                && subscription.getItems().getData().get(0).getPrice() != null
                && Objects.equals(priceId, subscription.getItems().getData().get(0).getPrice().getId());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key must contain at least one non-whitespace character");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must not exceed 128 characters");
        }
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
