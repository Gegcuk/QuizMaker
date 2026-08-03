package uk.gegc.quizmaker.features.billing.application;

import java.util.UUID;

/**
 * Verified checkout facts passed from the Stripe boundary to the transactional settlement service.
 */
public record CheckoutSessionSettlementCommand(
        String eventId,
        String stripeSessionId,
        String stripePaymentIntentId,
        String stripeCustomerId,
        UUID userId,
        UUID packId,
        long amountCents,
        String currency,
        long tokens,
        String sessionMetadata,
        boolean paid,
        UnpaidDisposition unpaidDisposition
) {

    public enum UnpaidDisposition {
        KEEP_PENDING,
        MARK_FAILED
    }
}
