package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.SubscriptionService;
import uk.gegc.quizmaker.features.billing.domain.model.SubscriptionStatus;
import uk.gegc.quizmaker.features.billing.infra.repository.SubscriptionStatusRepository;

import java.util.UUID;

/**
 * Implementation of subscription service for managing subscription lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private final BillingService billingService;
    private final BillingMetricsService metricsService;
    private final SubscriptionStatusRepository subscriptionStatusRepository;

    // Default tokens per period - in a real implementation, this would come from configuration
    private static final long DEFAULT_TOKENS_PER_PERIOD = 10000L;

    @Override
    public long getTokensPerPeriod(String subscriptionId, String priceId) {
        // In a real implementation, you'd look up the price ID in your product catalog
        // and return the appropriate token amount for that subscription tier
        
        // For now, return a default amount
        // You could also store this mapping in a database table
        return DEFAULT_TOKENS_PER_PERIOD;
    }

    @Override
    @Transactional
    public boolean handleSubscriptionPaymentSuccess(UUID userId, String subscriptionId, long periodStart, 
                                                  long tokensPerPeriod, String eventId) {
        try {
            // Build composite idempotency key to prevent double-crediting on retries
            String idempotencyKey = String.format("sub:%s:%d", subscriptionId, periodStart);
            
            // Build metadata for the subscription credit
            String metaJson = buildSubscriptionMetaJson(subscriptionId, periodStart, tokensPerPeriod);
            
            // Credit tokens using the billing service
            billingService.creditPurchase(userId, tokensPerPeriod, idempotencyKey, subscriptionId, metaJson);
            
            // Update subscription status
            updateSubscriptionStatus(userId, subscriptionId, true, "payment_succeeded");
            
            // Emit metrics
            metricsService.incrementTokensCredited(userId, tokensPerPeriod, "SUBSCRIPTION");
            
            log.info("Successfully credited {} tokens to user {} for subscription {} (period: {})", 
                    tokensPerPeriod, userId, subscriptionId, periodStart);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle subscription payment success for user {} subscription {}: {}", 
                    userId, subscriptionId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    @Transactional
    public void handleSubscriptionPaymentFailure(UUID userId, String subscriptionId, String reason) {
        try {
            // Block the subscription (soft lock)
            blockSubscription(userId, "payment_failed: " + reason);
            
            log.warn("Subscription payment failed for user {} subscription {}: {}", 
                    userId, subscriptionId, reason);
            
        } catch (Exception e) {
            log.error("Failed to handle subscription payment failure for user {} subscription {}: {}", 
                    userId, subscriptionId, e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void handleSubscriptionDeleted(UUID userId, String subscriptionId, String reason) {
        try {
            // Block the subscription (soft lock)
            blockSubscription(userId, "subscription_deleted: " + reason);
            
            log.info("Subscription deleted for user {} subscription {}: {}", 
                    userId, subscriptionId, reason);
            
        } catch (Exception e) {
            log.error("Failed to handle subscription deletion for user {} subscription {}: {}", 
                    userId, subscriptionId, e.getMessage(), e);
        }
    }

    @Override
    public boolean isSubscriptionActive(UUID userId) {
        return subscriptionStatusRepository.findByUserId(userId)
                .map(status -> !status.isBlocked())
                .orElse(true); // Default to active if no status record exists
    }

    @Override
    @Transactional
    public void blockSubscription(UUID userId, String reason) {
        SubscriptionStatus status = subscriptionStatusRepository.findByUserId(userId)
                .orElseGet(() -> {
                    SubscriptionStatus newStatus = new SubscriptionStatus();
                    newStatus.setUserId(userId);
                    newStatus.setSubscriptionId(null);
                    newStatus.setBlocked(false);
                    return newStatus;
                });
        
        status.setBlocked(true);
        status.setBlockReason(reason);
        subscriptionStatusRepository.save(status);
        
        log.info("Blocked subscription for user {}: {}", userId, reason);
    }

    @Override
    @Transactional
    public void unblockSubscription(UUID userId) {
        subscriptionStatusRepository.findByUserId(userId)
                .ifPresent(status -> {
                    status.setBlocked(false);
                    status.setBlockReason(null);
                    subscriptionStatusRepository.save(status);
                    
                    log.info("Unblocked subscription for user {}", userId);
                });
    }

    private void updateSubscriptionStatus(UUID userId, String subscriptionId, boolean active, String reason) {
        SubscriptionStatus status = subscriptionStatusRepository.findByUserId(userId)
                .orElseGet(() -> {
                    SubscriptionStatus newStatus = new SubscriptionStatus();
                    newStatus.setUserId(userId);
                    return newStatus;
                });
        
        status.setSubscriptionId(subscriptionId);
        status.setBlocked(!active);
        status.setBlockReason(active ? null : reason);
        subscriptionStatusRepository.save(status);
    }

    private String buildSubscriptionMetaJson(String subscriptionId, long periodStart, long tokensPerPeriod) {
        return String.format("""
                {
                    "type": "subscription_credit",
                    "subscriptionId": "%s",
                    "periodStart": %d,
                    "tokensPerPeriod": %d,
                    "timestamp": %d
                }
                """, subscriptionId, periodStart, tokensPerPeriod, System.currentTimeMillis());
    }
}
