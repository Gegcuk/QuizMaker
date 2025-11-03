package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CheckoutSessionResponse", description = "Stripe checkout session details")
public record CheckoutSessionResponse(
        @Schema(description = "Stripe checkout page URL", example = "https://checkout.stripe.com/...")
        String url,
        
        @Schema(description = "Stripe session ID", example = "cs_test_...")
        String sessionId
) {}
