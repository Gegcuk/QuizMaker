package uk.gegc.quizmaker.features.billing.api.dto;

import java.util.UUID;

public record PackDto(
        UUID id,
        String name,
        String description,
        long tokens,
        long priceCents,
        String currency,
        String stripePriceId
) {}
