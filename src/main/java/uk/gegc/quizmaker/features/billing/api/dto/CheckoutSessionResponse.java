package uk.gegc.quizmaker.features.billing.api.dto;

public record CheckoutSessionResponse(
        String url,
        String sessionId
) {}

