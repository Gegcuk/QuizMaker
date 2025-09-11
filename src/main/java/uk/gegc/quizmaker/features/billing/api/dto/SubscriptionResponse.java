package uk.gegc.quizmaker.features.billing.api.dto;

public record SubscriptionResponse(
        String subscriptionId,
        String clientSecret
) {}
