package uk.gegc.quizmaker.features.auth.domain.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uk.gegc.quizmaker.features.billing.application.BillingService;

import java.util.UUID;

/**
 * Listens for {@link UserRegisteredEvent} instances and credits registration bonus tokens
 * after the user registration transaction has been committed.
 * <p>
 * Uses {@code TransactionPhase.AFTER_COMMIT} to ensure the user is fully committed
 * before attempting to create the Balance record, avoiding foreign key constraint violations
 * and lock conflicts.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserRegisteredEventListener {

    private final BillingService billingService;

    @Value("${app.auth.registration-bonus-tokens:100}")
    private long registrationBonusTokens;

    private static final String REGISTRATION_BONUS_REF = "registration-bonus";

    @Async("generalTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserRegistered(UserRegisteredEvent event) {
        UUID userId = event.getUserId();
        try {
            String idempotencyKey = REGISTRATION_BONUS_REF + ":" + userId;
            billingService.creditAdjustment(userId, registrationBonusTokens, idempotencyKey, REGISTRATION_BONUS_REF, null);
            log.info("Credited {} registration bonus tokens to user {}", registrationBonusTokens, userId);
        } catch (Exception e) {
            // Log error but don't fail - registration already succeeded
            // The error will also be logged by the billing service
            log.warn("Failed to credit registration bonus tokens for user {}: {}", userId, e.getMessage());
        }
    }
}

