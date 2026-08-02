package uk.gegc.quizmaker.features.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;

/**
 * Request DTO for creating a Stripe checkout session for token pack purchases.
 */
@Schema(name = "CreateCheckoutSessionRequest", description = "Request to create a Stripe checkout session for purchasing token packs")
public record CreateCheckoutSessionRequest(
        @Schema(description = "Deprecated legacy Stripe Price ID. When supplied with packId it must match that pack. New clients should send packId only.", deprecated = true, maxLength = 100, example = "price_1234567890")
        @Size(max = 100, message = "Price ID must be at most 100 characters")
        String priceId,

        @Schema(description = "Preferred server-owned active token pack identifier. Its configured Stripe price, currency, amount, and token entitlement are used together.", requiredMode = RequiredMode.NOT_REQUIRED, example = "550e8400-e29b-41d4-a716-446655440000")
        java.util.UUID packId
) {
    @AssertTrue(message = "Either packId or legacy priceId must be provided")
    public boolean isPackReferenceProvided() {
        return packId != null || StringUtils.hasText(priceId);
    }
}
