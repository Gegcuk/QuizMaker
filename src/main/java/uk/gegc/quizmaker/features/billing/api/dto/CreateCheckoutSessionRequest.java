package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for creating a Stripe checkout session for token pack purchases.
 */
@Schema(name = "CreateCheckoutSessionRequest", description = "Request to create a Stripe checkout session for purchasing token packs")
public record CreateCheckoutSessionRequest(
        @Schema(description = "Stripe Price ID for the token pack", example = "price_1234567890", required = true)
        @NotBlank(message = "Price ID is required")
        String priceId,
        
        @Schema(description = "Optional internal ProductPack ID for metadata", example = "550e8400-e29b-41d4-a716-446655440000")
        java.util.UUID packId
) {}
