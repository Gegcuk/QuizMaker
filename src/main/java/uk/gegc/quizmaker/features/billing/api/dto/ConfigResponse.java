package uk.gegc.quizmaker.features.billing.api.dto;

import java.util.List;

public record ConfigResponse(
        String publishableKey,
        List<PackDto> prices
) {}
