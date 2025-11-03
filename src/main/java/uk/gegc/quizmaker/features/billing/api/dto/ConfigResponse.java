package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ConfigResponse", description = "Billing configuration and available token packs")
public record ConfigResponse(
        @Schema(description = "Stripe publishable key for client-side", example = "pk_test_...")
        String publishableKey,
        
        @Schema(description = "Available token packs for purchase")
        List<PackDto> prices
) {}
