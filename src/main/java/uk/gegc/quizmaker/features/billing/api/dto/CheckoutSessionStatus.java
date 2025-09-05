package uk.gegc.quizmaker.features.billing.api.dto;

public record CheckoutSessionStatus(
        String sessionId,
        String status,
        boolean credited,
        Long creditedTokens
) {}

