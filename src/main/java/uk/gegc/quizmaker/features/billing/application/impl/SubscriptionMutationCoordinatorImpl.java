package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationClaim;
import uk.gegc.quizmaker.features.billing.application.SubscriptionMutationCoordinator;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.billing.domain.exception.SubscriptionMutationConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationOperation;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationState;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationType;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionMutationOperationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionStatusRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionMutationCoordinatorImpl implements SubscriptionMutationCoordinator {

    private static final Duration EXECUTION_LEASE = Duration.ofSeconds(30);
    private static final String CANCELLED_BY_USER = "subscription_cancelled_by_user";

    private final SubscriptionMutationOperationRepository operationRepository;
    private final SubscriptionStatusRepository subscriptionStatusRepository;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Override
    public SubscriptionMutationClaim claim(
            UUID userId,
            String subscriptionId,
            SubscriptionMutationType operationType,
            String targetPriceId,
            String idempotencyKey,
            boolean remoteAlreadyApplied
    ) {
        validateClaim(userId, subscriptionId, operationType, targetPriceId);
        String keyHash = idempotencyKey == null ? null : sha256(idempotencyKey);
        String requestHash = requestHash(subscriptionId, operationType, targetPriceId);

        return Objects.requireNonNull(requiresNew().execute(status -> claimInTransaction(
                userId,
                subscriptionId,
                operationType,
                targetPriceId,
                keyHash,
                requestHash,
                remoteAlreadyApplied
        )));
    }

    @Override
    public void complete(UUID operationId, UUID userId) {
        requiresNew().executeWithoutResult(status -> {
            SubscriptionStatus localStatus = lockLocalStatus(userId);
            SubscriptionMutationOperation operation = lockOperation(operationId, userId);
            verifyLocalMapping(localStatus, operation.getSubscriptionId());
            completeOperation(operation, localStatus, LocalDateTime.now(clock));
        });
    }

    @Override
    public void makeRetryable(UUID operationId, UUID userId) {
        requiresNew().executeWithoutResult(status -> {
            lockLocalStatus(userId);
            SubscriptionMutationOperation operation = lockOperation(operationId, userId);
            operation.makeRetryable(LocalDateTime.now(clock));
            operationRepository.save(operation);
        });
    }

    @Override
    public Optional<SubscriptionMutationState> getState(UUID operationId, UUID userId) {
        return operationRepository.findByIdAndUserId(operationId, userId)
                .map(SubscriptionMutationOperation::getState);
    }

    private SubscriptionMutationClaim claimInTransaction(
            UUID userId,
            String subscriptionId,
            SubscriptionMutationType operationType,
            String targetPriceId,
            String keyHash,
            String requestHash,
            boolean remoteAlreadyApplied
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        SubscriptionStatus localStatus = lockLocalStatus(userId);
        verifyLocalMapping(localStatus, subscriptionId);

        if (keyHash != null) {
            Optional<SubscriptionMutationOperation> keyed =
                    operationRepository.findByUserIdAndIdempotencyKeyHash(userId, keyHash);
            if (keyed.isPresent()) {
                return handleExisting(keyed.get(), localStatus, requestHash, remoteAlreadyApplied, now);
            }
        } else {
            Optional<SubscriptionMutationOperation> retryable = operationRepository
                    .findFirstByUserIdAndSubscriptionIdAndRequestHashAndStateOrderByCreatedAtDesc(
                            userId, subscriptionId, requestHash, SubscriptionMutationState.RETRYABLE);
            if (retryable.isPresent()) {
                return handleExisting(retryable.get(), localStatus, requestHash, remoteAlreadyApplied, now);
            }
        }

        Optional<SubscriptionMutationOperation> active = operationRepository
                .findFirstByUserIdAndSubscriptionIdAndStateOrderByCreatedAtAsc(
                        userId, subscriptionId, SubscriptionMutationState.IN_PROGRESS);
        if (active.isPresent()) {
            SubscriptionMutationOperation existing = active.get();
            if (remoteAlreadyApplied && existing.hasSameRequest(requestHash)) {
                completeOperation(existing, localStatus, now);
                if (keyHash == null) {
                    return replay(existing);
                }
            } else {
                return handleConcurrent(existing, operationType, requestHash, now);
            }
        }

        if (keyHash == null && (remoteAlreadyApplied || operationType == SubscriptionMutationType.CANCEL)) {
            Optional<SubscriptionMutationOperation> completed = operationRepository
                    .findFirstByUserIdAndSubscriptionIdAndRequestHashAndStateOrderByCreatedAtDesc(
                            userId, subscriptionId, requestHash, SubscriptionMutationState.SUCCEEDED);
            if (completed.isPresent()) {
                ensureLocallyCancelled(completed.get(), localStatus);
                // A concurrent caller may still hold the pre-cancellation provider snapshot.
                return remoteAlreadyApplied ? replay(completed.get()) : waitFor(completed.get());
            }
        }

        SubscriptionMutationOperation created = new SubscriptionMutationOperation(
                localStatus.getId(),
                userId,
                subscriptionId,
                operationType,
                targetPriceId,
                keyHash,
                requestHash,
                "qm-sub-mut-" + UUID.randomUUID(),
                remoteAlreadyApplied ? SubscriptionMutationState.SUCCEEDED : SubscriptionMutationState.IN_PROGRESS,
                now,
                remoteAlreadyApplied ? null : now.plus(EXECUTION_LEASE)
        );
        operationRepository.saveAndFlush(created);
        ensureLocallyCancelled(created, localStatus);
        return remoteAlreadyApplied ? replay(created) : execute(created);
    }

    private SubscriptionMutationClaim handleExisting(
            SubscriptionMutationOperation existing,
            SubscriptionStatus localStatus,
            String requestHash,
            boolean remoteAlreadyApplied,
            LocalDateTime now
    ) {
        if (!existing.hasSameRequest(requestHash)) {
            throw new IdempotencyConflictException(
                    "This Idempotency-Key was already used for a different subscription mutation.");
        }
        if (existing.getState() == SubscriptionMutationState.SUCCEEDED) {
            ensureLocallyCancelled(existing, localStatus);
            return replay(existing);
        }
        if (remoteAlreadyApplied) {
            completeOperation(existing, localStatus, now);
            return replay(existing);
        }
        if (existing.hasActiveLease(now)) {
            return waitFor(existing);
        }

        existing.acquire(now, now.plus(EXECUTION_LEASE));
        operationRepository.save(existing);
        return execute(existing);
    }

    private SubscriptionMutationClaim handleConcurrent(
            SubscriptionMutationOperation active,
            SubscriptionMutationType requestedType,
            String requestHash,
            LocalDateTime now
    ) {
        if (active.hasSameRequest(requestHash)) {
            if (!active.hasActiveLease(now)) {
                active.acquire(now, now.plus(EXECUTION_LEASE));
                operationRepository.save(active);
                return execute(active);
            }
            return waitFor(active);
        }
        if (active.getOperationType() == SubscriptionMutationType.CANCEL
                || requestedType == SubscriptionMutationType.CANCEL) {
            if (!active.hasActiveLease(now)) {
                active.makeRetryable(now);
                operationRepository.save(active);
            }
            return waitFor(active);
        }
        if (!active.hasActiveLease(now)) {
            active.makeRetryable(now);
            operationRepository.save(active);
            return waitFor(active);
        }
        throw new SubscriptionMutationConflictException(
                "A different subscription update is already in progress");
    }

    private void completeOperation(
            SubscriptionMutationOperation operation,
            SubscriptionStatus localStatus,
            LocalDateTime now
    ) {
        operation.succeed(now);
        operationRepository.save(operation);
        ensureLocallyCancelled(operation, localStatus);
    }

    private void ensureLocallyCancelled(
            SubscriptionMutationOperation operation,
            SubscriptionStatus localStatus
    ) {
        if (operation.getState() != SubscriptionMutationState.SUCCEEDED
                || operation.getOperationType() != SubscriptionMutationType.CANCEL) {
            return;
        }
        if (!localStatus.isBlocked() || !CANCELLED_BY_USER.equals(localStatus.getBlockReason())) {
            localStatus.setBlocked(true);
            localStatus.setBlockReason(CANCELLED_BY_USER);
            subscriptionStatusRepository.save(localStatus);
        }
    }

    private SubscriptionStatus lockLocalStatus(UUID userId) {
        return subscriptionStatusRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ForbiddenException("Subscription mutation is not permitted"));
    }

    private SubscriptionMutationOperation lockOperation(UUID operationId, UUID userId) {
        return operationRepository.findByIdAndUserIdForUpdate(operationId, userId)
                .orElseThrow(() -> new IllegalStateException("Subscription mutation operation is unavailable"));
    }

    private void verifyLocalMapping(SubscriptionStatus status, String subscriptionId) {
        if (!subscriptionId.equals(status.getSubscriptionId())) {
            throw new ForbiddenException("Subscription mutation is not permitted");
        }
    }

    private SubscriptionMutationClaim execute(SubscriptionMutationOperation operation) {
        return new SubscriptionMutationClaim(
                operation.getId(), SubscriptionMutationClaim.Action.EXECUTE, operation.getStripeIdempotencyKey());
    }

    private SubscriptionMutationClaim waitFor(SubscriptionMutationOperation operation) {
        return new SubscriptionMutationClaim(
                operation.getId(), SubscriptionMutationClaim.Action.WAIT, operation.getStripeIdempotencyKey());
    }

    private SubscriptionMutationClaim replay(SubscriptionMutationOperation operation) {
        return new SubscriptionMutationClaim(
                operation.getId(), SubscriptionMutationClaim.Action.REPLAY, operation.getStripeIdempotencyKey());
    }

    private void validateClaim(
            UUID userId,
            String subscriptionId,
            SubscriptionMutationType operationType,
            String targetPriceId
    ) {
        if (userId == null || !StringUtils.hasText(subscriptionId) || operationType == null) {
            throw new IllegalArgumentException("A user, subscription, and mutation type are required");
        }
        if (operationType == SubscriptionMutationType.UPDATE && !StringUtils.hasText(targetPriceId)) {
            throw new IllegalArgumentException("A target price is required for subscription updates");
        }
    }

    private String requestHash(
            String subscriptionId,
            SubscriptionMutationType operationType,
            String targetPriceId
    ) {
        return sha256(operationType.name() + "\n" + subscriptionId + "\n"
                + Objects.toString(targetPriceId, ""));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }
}
