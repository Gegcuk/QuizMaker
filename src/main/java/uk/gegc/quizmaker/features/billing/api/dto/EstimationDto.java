package uk.gegc.quizmaker.features.billing.api.dto;

public record EstimationDto(
        long estimatedLlmTokens,
        long estimatedBillingTokens,
        Long approxCostCents,
        String currency,
        boolean estimate
) {}

