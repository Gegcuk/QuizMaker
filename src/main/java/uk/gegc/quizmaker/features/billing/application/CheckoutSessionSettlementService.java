package uk.gegc.quizmaker.features.billing.application;

/**
 * Applies the economic outcome of a server-created Stripe Checkout Session exactly once.
 * Event delivery deduplication is intentionally separate from session-level settlement.
 */
public interface CheckoutSessionSettlementService {

    SettlementResult settle(CheckoutSessionSettlementCommand command);

    enum SettlementResult {
        CREDITED,
        ALREADY_SETTLED,
        PENDING,
        FAILED,
        DUPLICATE_EVENT
    }
}
