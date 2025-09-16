package uk.gegc.quizmaker.features.billing.application;

import java.util.UUID;

/**
 * Service for emitting billing metrics and counters.
 * Provides structured metrics for observability and monitoring.
 */
public interface BillingMetricsService {

    /**
     * Increment reservation counters.
     */
    void incrementReservationCreated(UUID userId, long amount);
    void incrementReservationCommitted(UUID userId, long amount);
    void incrementReservationReleased(UUID userId, long amount);

    /**
     * Increment token operation counters.
     */
    void incrementTokensPurchased(UUID userId, long amount, String source);
    void incrementTokensCredited(UUID userId, long amount, String source);
    void incrementTokensCommitted(UUID userId, long amount, String source);
    void incrementTokensReleased(UUID userId, long amount, String source);
    void incrementTokensAdjusted(UUID userId, long amount, String source);

    /**
     * Increment webhook counters.
     */
    void incrementWebhookReceived(String eventType);
    void incrementWebhookOk(String eventType);
    void incrementWebhookDuplicate(String eventType);
    void incrementWebhookFailed(String eventType);
    
    /**
     * Record webhook latency metrics.
     */
    void recordWebhookLatency(String eventType, long latencyMs);

    /**
     * Record balance metrics.
     */
    void recordBalanceAvailable(UUID userId, long amount);
    void recordBalanceReserved(UUID userId, long amount);
    void recordNegativeBalance(UUID userId, long amount);

    /**
     * Record reconciliation metrics.
     */
    void recordReconciliationDrift(UUID userId, long driftAmount);
    void recordReconciliationSuccess(UUID userId);
    void recordReconciliationFailure(UUID userId, String reason);

    /**
     * Record alerting metrics.
     */
    void recordWebhookFailureRate(String eventType, double failureRate);
    void recordSweeperBacklog(int expiredReservations);
    void recordNegativeBalanceAlert(UUID userId, long negativeAmount);
}
