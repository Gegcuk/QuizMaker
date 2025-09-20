package uk.gegc.quizmaker.features.billing.application;

import java.util.UUID;

import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;

/**
 * Internal billing service for system operations.
 * This service is intended for use by webhooks, subscription services, and other internal systems.
 * It does not require user authentication and should not be exposed to controllers.
 */
public interface InternalBillingService {

    ReservationDto reserve(UUID userId, long estimatedBillingTokens, String ref, String idempotencyKey);

    CommitResultDto commit(UUID reservationId, long actualBillingTokens, String ref, String idempotencyKey);

    ReleaseResultDto release(UUID reservationId, String reason, String ref, String idempotencyKey);

    /**
     * Credit tokens to a user's account from a purchase.
     * This is an internal operation used by webhooks and subscription services.
     * 
     * @param userId the user ID
     * @param tokens the number of tokens to credit
     * @param idempotencyKey the idempotency key to prevent duplicate processing
     * @param ref reference information for the transaction
     * @param metaJson additional metadata as JSON
     */
    void creditPurchase(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson);

    /**
     * Deduct tokens from a user's account.
     * This is an internal operation used by refund services and adjustments.
     * 
     * @param userId the user ID
     * @param tokens the number of tokens to deduct
     * @param idempotencyKey the idempotency key to prevent duplicate processing
     * @param ref reference information for the transaction
     * @param metaJson additional metadata as JSON
     */
    void deductTokens(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson);
}

