package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CheckoutSessionStatus", description = "Status of a checkout session")
public record CheckoutSessionStatus(
        @Schema(description = "Stripe session ID", example = "cs_test_...")
        String sessionId,
        
        @Schema(description = "Session status", example = "complete")
        String status,
        
        @Schema(description = "Whether tokens have been credited to user balance", example = "true")
        boolean credited,
        
        @Schema(description = "Number of tokens credited (null if not credited)", example = "10000")
        Long creditedTokens
) {}
