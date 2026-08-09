package uk.gegc.quizmaker.features.billing.application;

import com.stripe.model.checkout.Session;

import java.util.UUID;

/**
 * Service for validating checkout sessions and ensuring data integrity
 * in the checkout→credit path.
 */
public interface CheckoutValidationService {

    /**
     * Validates one Stripe line item against the immutable payment snapshot captured when
     * the Checkout Session was created. Later catalog changes do not alter settlement.
     * 
     * @param session the Stripe checkout session
     * @param packIdFromMetadata the metadata pack ID to cross-check against the snapshot (if any)
     * @return validation result containing the original purchase facts
     * @throws InvalidCheckoutSessionException if validation fails
     */
    CheckoutValidationResult validateAndResolvePack(Session session, UUID packIdFromMetadata);

    /**
     * Immutable purchase facts proven by both the payment snapshot and retrieved Stripe Session.
     */
    record CheckoutValidationResult(
            UUID packId,
            String stripePriceId,
            long totalAmountCents,
            String currency,
            long totalTokens
    ) {}
}
