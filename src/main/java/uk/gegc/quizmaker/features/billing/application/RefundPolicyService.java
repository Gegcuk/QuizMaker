package uk.gegc.quizmaker.features.billing.application;

import uk.gegc.quizmaker.features.billing.api.dto.RefundCalculationDto;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;

/**
 * Service for handling refund policies and calculations.
 * Implements business rules for token refunds based on usage.
 */
public interface RefundPolicyService {

    /**
     * Calculate refund amount based on token usage and refund policy.
     *
     * @param payment the original payment
     * @param refundAmountCents the requested refund amount in cents
     * @return refund calculation details
     */
    RefundCalculationDto calculateRefund(Payment payment, long refundAmountCents);

    /**
     * Process a refund according to the calculated policy.
     *
     * @param payment the original payment
     * @param calculation the refund calculation
     * @param refundId the refund identifier (e.g., Stripe refund ID)
     * @param eventId the webhook event ID for idempotency
     */
    void processRefund(Payment payment, RefundCalculationDto calculation, String refundId, String eventId);

    /**
     * Get the current refund policy setting.
     *
     * @return the refund policy enum value
     */
    RefundPolicy getRefundPolicy();
}
