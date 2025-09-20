package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;

import java.util.UUID;

/**
 * Implementation of internal billing service.
 * This service delegates to the concrete BillingServiceImpl for internal operations
 * without requiring user authentication.
 */
@Service
@RequiredArgsConstructor
public class InternalBillingServiceImpl implements InternalBillingService {

    private final BillingServiceImpl billingServiceImpl;

    @Override
    public ReservationDto reserve(UUID userId, long estimatedBillingTokens, String ref, String idempotencyKey) {
        return billingServiceImpl.reserveInternal(userId, estimatedBillingTokens, ref, idempotencyKey);
    }

    @Override
    public CommitResultDto commit(UUID reservationId, long actualBillingTokens, String ref, String idempotencyKey) {
        return billingServiceImpl.commitInternal(reservationId, actualBillingTokens, ref, idempotencyKey);
    }

    @Override
    public ReleaseResultDto release(UUID reservationId, String reason, String ref, String idempotencyKey) {
        return billingServiceImpl.releaseInternal(reservationId, reason, ref, idempotencyKey);
    }

    @Override
    public void creditPurchase(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson) {
        billingServiceImpl.creditPurchase(userId, tokens, idempotencyKey, ref, metaJson);
    }

    @Override
    public void deductTokens(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson) {
        billingServiceImpl.deductTokens(userId, tokens, idempotencyKey, ref, metaJson);
    }
}
