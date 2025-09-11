package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
    public void creditPurchase(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson) {
        billingServiceImpl.creditPurchase(userId, tokens, idempotencyKey, ref, metaJson);
    }

    @Override
    public void deductTokens(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson) {
        billingServiceImpl.deductTokens(userId, tokens, idempotencyKey, ref, metaJson);
    }
}
