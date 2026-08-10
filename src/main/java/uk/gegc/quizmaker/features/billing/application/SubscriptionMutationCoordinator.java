package uk.gegc.quizmaker.features.billing.application;

import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationState;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionMutationType;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionMutationCoordinator {

    SubscriptionMutationClaim claim(
            UUID userId,
            String subscriptionId,
            SubscriptionMutationType operationType,
            String targetPriceId,
            String idempotencyKey,
            boolean remoteAlreadyApplied
    );

    void complete(UUID operationId, UUID userId);

    void makeRetryable(UUID operationId, UUID userId);

    Optional<SubscriptionMutationState> getState(UUID operationId, UUID userId);
}
