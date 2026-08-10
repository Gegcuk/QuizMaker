package uk.gegc.quizmaker.features.billing.application;

import java.util.UUID;

public record SubscriptionMutationClaim(
        UUID operationId,
        Action action,
        String stripeIdempotencyKey
) {
    public enum Action {
        EXECUTE,
        WAIT,
        REPLAY
    }
}
