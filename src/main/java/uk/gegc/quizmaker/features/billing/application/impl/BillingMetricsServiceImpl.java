package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;

import java.util.UUID;

/**
 * Implementation of billing metrics service.
 * Emits structured metrics for observability and monitoring.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingMetricsServiceImpl implements BillingMetricsService {

    @Override
    public void incrementReservationCreated(UUID userId, long amount) {
        log.info("METRIC: billing.reservations.created userId={} amount={}", userId, amount);
        // In a real implementation, you'd emit to your metrics system (Prometheus, CloudWatch, etc.)
    }

    @Override
    public void incrementReservationCommitted(UUID userId, long amount) {
        log.info("METRIC: billing.reservations.committed userId={} amount={}", userId, amount);
    }

    @Override
    public void incrementReservationReleased(UUID userId, long amount) {
        log.info("METRIC: billing.reservations.released userId={} amount={}", userId, amount);
    }

    @Override
    public void incrementTokensPurchased(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.purchased userId={} amount={} source={}", userId, amount, source);
    }

    @Override
    public void incrementTokensCredited(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.credited userId={} amount={} source={}", userId, amount, source);
    }

    @Override
    public void incrementTokensCommitted(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.committed userId={} amount={} source={}", userId, amount, source);
    }

    @Override
    public void incrementTokensReleased(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.released userId={} amount={} source={}", userId, amount, source);
    }

    @Override
    public void incrementTokensAdjusted(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.adjusted userId={} amount={} source={}", userId, amount, source);
    }

    @Override
    public void incrementWebhookReceived(String eventType) {
        log.info("METRIC: stripe.webhooks.received eventType={}", eventType);
    }

    @Override
    public void incrementWebhookOk(String eventType) {
        log.info("METRIC: stripe.webhooks.ok eventType={}", eventType);
    }

    @Override
    public void incrementWebhookDuplicate(String eventType) {
        log.info("METRIC: stripe.webhooks.duplicate eventType={}", eventType);
    }

    @Override
    public void incrementWebhookFailed(String eventType) {
        log.info("METRIC: stripe.webhooks.failed eventType={}", eventType);
    }

    @Override
    public void recordWebhookLatency(String eventType, long latencyMs) {
        log.info("METRIC: stripe.webhooks.latency eventType={} latencyMs={}", eventType, latencyMs);
    }

    @Override
    public void recordBalanceAvailable(UUID userId, long amount) {
        log.info("METRIC: billing.balance.available userId={} amount={}", userId, amount);
    }

    @Override
    public void recordBalanceReserved(UUID userId, long amount) {
        log.info("METRIC: billing.balance.reserved userId={} amount={}", userId, amount);
    }

    @Override
    public void recordNegativeBalance(UUID userId, long amount) {
        log.warn("METRIC: billing.balance.negative userId={} amount={}", userId, amount);
    }

    @Override
    public void recordReconciliationDrift(UUID userId, long driftAmount) {
        log.warn("METRIC: billing.reconciliation.drift userId={} driftAmount={}", userId, driftAmount);
    }

    @Override
    public void recordReconciliationSuccess(UUID userId) {
        log.info("METRIC: billing.reconciliation.success userId={}", userId);
    }

    @Override
    public void recordReconciliationFailure(UUID userId, String reason) {
        log.error("METRIC: billing.reconciliation.failure userId={} reason={}", userId, reason);
    }

    @Override
    public void recordWebhookFailureRate(String eventType, double failureRate) {
        log.warn("METRIC: billing.webhook.failure_rate eventType={} failureRate={}", eventType, failureRate);
    }

    @Override
    public void recordSweeperBacklog(int expiredReservations) {
        log.warn("METRIC: billing.sweeper.backlog expiredReservations={}", expiredReservations);
    }

    @Override
    public void recordNegativeBalanceAlert(UUID userId, long negativeAmount) {
        log.warn("METRIC: billing.balance.negative_alert userId={} negativeAmount={}", userId, negativeAmount);
    }
}
