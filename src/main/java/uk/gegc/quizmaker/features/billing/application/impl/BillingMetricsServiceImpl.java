package uk.gegc.quizmaker.features.billing.application.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implementation of billing metrics service.
 * Emits structured metrics for observability and monitoring using Micrometer.
 */
@Slf4j
@Service
public class BillingMetricsServiceImpl implements BillingMetricsService {

    private final MeterRegistry meterRegistry;
    
    // Counters for tracking various billing events
    private final Counter reservationCreatedCounter;
    private final Counter reservationCommittedCounter;
    private final Counter reservationReleasedCounter;
    private final Counter tokensPurchasedCounter;
    private final Counter tokensCreditedCounter;
    private final Counter tokensCommittedCounter;
    private final Counter tokensReleasedCounter;
    private final Counter tokensAdjustedCounter;
    private final Counter webhookReceivedCounter;
    private final Counter webhookOkCounter;
    private final Counter webhookDuplicateCounter;
    private final Counter webhookFailedCounter;
    private final Counter reconciliationSuccessCounter;
    private final Counter reconciliationFailureCounter;
    
    // Timers for measuring latency
    private final Timer webhookLatencyTimer;
    
    // Gauges for tracking current state
    private final ConcurrentHashMap<String, AtomicLong> balanceGauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> negativeBalanceGauges = new ConcurrentHashMap<>();
    
    public BillingMetricsServiceImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // Initialize counters
        this.reservationCreatedCounter = Counter.builder("billing.reservations.created")
                .description("Number of token reservations created")
                .register(meterRegistry);
        this.reservationCommittedCounter = Counter.builder("billing.reservations.committed")
                .description("Number of token reservations committed")
                .register(meterRegistry);
        this.reservationReleasedCounter = Counter.builder("billing.reservations.released")
                .description("Number of token reservations released")
                .register(meterRegistry);
        this.tokensPurchasedCounter = Counter.builder("billing.tokens.purchased")
                .description("Number of tokens purchased")
                .register(meterRegistry);
        this.tokensCreditedCounter = Counter.builder("billing.tokens.credited")
                .description("Number of tokens credited")
                .register(meterRegistry);
        this.tokensCommittedCounter = Counter.builder("billing.tokens.committed")
                .description("Number of tokens committed")
                .register(meterRegistry);
        this.tokensReleasedCounter = Counter.builder("billing.tokens.released")
                .description("Number of tokens released")
                .register(meterRegistry);
        this.tokensAdjustedCounter = Counter.builder("billing.tokens.adjusted")
                .description("Number of tokens adjusted")
                .register(meterRegistry);
        this.webhookReceivedCounter = Counter.builder("stripe.webhooks.received")
                .description("Number of Stripe webhooks received")
                .register(meterRegistry);
        this.webhookOkCounter = Counter.builder("stripe.webhooks.ok")
                .description("Number of successful Stripe webhook processing")
                .register(meterRegistry);
        this.webhookDuplicateCounter = Counter.builder("stripe.webhooks.duplicate")
                .description("Number of duplicate Stripe webhooks")
                .register(meterRegistry);
        this.webhookFailedCounter = Counter.builder("stripe.webhooks.failed")
                .description("Number of failed Stripe webhook processing")
                .register(meterRegistry);
        this.reconciliationSuccessCounter = Counter.builder("billing.reconciliation.success")
                .description("Number of successful reconciliations")
                .register(meterRegistry);
        this.reconciliationFailureCounter = Counter.builder("billing.reconciliation.failure")
                .description("Number of failed reconciliations")
                .register(meterRegistry);
        
        // Initialize timer
        this.webhookLatencyTimer = Timer.builder("stripe.webhooks.latency")
                .description("Stripe webhook processing latency")
                .register(meterRegistry);
    }

    @Override
    public void incrementReservationCreated(UUID userId, long amount) {
        log.info("METRIC: billing.reservations.created userId={} amount={}", userId, amount);
        reservationCreatedCounter.increment(amount);
    }

    @Override
    public void incrementReservationCommitted(UUID userId, long amount) {
        log.info("METRIC: billing.reservations.committed userId={} amount={}", userId, amount);
        reservationCommittedCounter.increment(amount);
    }

    @Override
    public void incrementReservationReleased(UUID userId, long amount) {
        log.info("METRIC: billing.reservations.released userId={} amount={}", userId, amount);
        reservationReleasedCounter.increment(amount);
    }

    @Override
    public void incrementTokensPurchased(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.purchased userId={} amount={} source={}", userId, amount, source);
        tokensPurchasedCounter.increment(amount);
    }

    @Override
    public void incrementTokensCredited(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.credited userId={} amount={} source={}", userId, amount, source);
        tokensCreditedCounter.increment(amount);
    }

    @Override
    public void incrementTokensCommitted(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.committed userId={} amount={} source={}", userId, amount, source);
        tokensCommittedCounter.increment(amount);
    }

    @Override
    public void incrementTokensReleased(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.released userId={} amount={} source={}", userId, amount, source);
        tokensReleasedCounter.increment(amount);
    }

    @Override
    public void incrementTokensAdjusted(UUID userId, long amount, String source) {
        log.info("METRIC: billing.tokens.adjusted userId={} amount={} source={}", userId, amount, source);
        tokensAdjustedCounter.increment(amount);
    }

    @Override
    public void incrementWebhookReceived(String eventType) {
        log.info("METRIC: stripe.webhooks.received eventType={}", eventType);
        webhookReceivedCounter.increment();
    }

    @Override
    public void incrementWebhookOk(String eventType) {
        log.info("METRIC: stripe.webhooks.ok eventType={}", eventType);
        webhookOkCounter.increment();
    }

    @Override
    public void incrementWebhookDuplicate(String eventType) {
        log.info("METRIC: stripe.webhooks.duplicate eventType={}", eventType);
        webhookDuplicateCounter.increment();
    }

    @Override
    public void incrementWebhookFailed(String eventType) {
        log.info("METRIC: stripe.webhooks.failed eventType={}", eventType);
        webhookFailedCounter.increment();
    }

    @Override
    public void recordWebhookLatency(String eventType, long latencyMs) {
        log.info("METRIC: stripe.webhooks.latency eventType={} latencyMs={}", eventType, latencyMs);
        webhookLatencyTimer.record(latencyMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordBalanceAvailable(UUID userId, long amount) {
        log.info("METRIC: billing.balance.available userId={} amount={}", userId, amount);
        String userIdStr = userId.toString();
        balanceGauges.computeIfAbsent(userIdStr, k -> {
            AtomicLong gauge = new AtomicLong(amount);
            Gauge.builder("billing.balance.available", gauge, AtomicLong::get)
                    .description("Available token balance for user")
                    .tag("userId", userIdStr)
                    .register(meterRegistry);
            return gauge;
        }).set(amount);
    }

    @Override
    public void recordBalanceReserved(UUID userId, long amount) {
        log.info("METRIC: billing.balance.reserved userId={} amount={}", userId, amount);
        String userIdStr = userId.toString();
        Gauge.builder("billing.balance.reserved", () -> amount)
                .description("Reserved token balance for user")
                .tag("userId", userIdStr)
                .register(meterRegistry);
    }

    @Override
    public void recordNegativeBalance(UUID userId, long amount) {
        log.warn("METRIC: billing.balance.negative userId={} amount={}", userId, amount);
        String userIdStr = userId.toString();
        negativeBalanceGauges.computeIfAbsent(userIdStr, k -> {
            AtomicLong gauge = new AtomicLong(amount);
            Gauge.builder("billing.balance.negative", gauge, AtomicLong::get)
                    .description("Negative token balance for user")
                    .tag("userId", userIdStr)
                    .register(meterRegistry);
            return gauge;
        }).set(amount);
    }

    @Override
    public void recordReconciliationDrift(UUID userId, long driftAmount) {
        log.warn("METRIC: billing.reconciliation.drift userId={} driftAmount={}", userId, driftAmount);
        Gauge.builder("billing.reconciliation.drift", () -> driftAmount)
                .description("Reconciliation drift amount for user")
                .tag("userId", userId.toString())
                .register(meterRegistry);
    }

    @Override
    public void recordReconciliationSuccess(UUID userId) {
        log.info("METRIC: billing.reconciliation.success userId={}", userId);
        reconciliationSuccessCounter.increment();
    }

    @Override
    public void recordReconciliationFailure(UUID userId, String reason) {
        log.error("METRIC: billing.reconciliation.failure userId={} reason={}", userId, reason);
        reconciliationFailureCounter.increment();
    }

    @Override
    public void recordWebhookFailureRate(String eventType, double failureRate) {
        log.warn("METRIC: billing.webhook.failure_rate eventType={} failureRate={}", eventType, failureRate);
        Gauge.builder("billing.webhook.failure_rate", () -> failureRate)
                .description("Webhook failure rate by event type")
                .tag("eventType", eventType)
                .register(meterRegistry);
    }

    @Override
    public void recordSweeperBacklog(int expiredReservations) {
        log.warn("METRIC: billing.sweeper.backlog expiredReservations={}", expiredReservations);
        Gauge.builder("billing.sweeper.backlog", () -> expiredReservations)
                .description("Number of expired reservations in sweeper backlog")
                .register(meterRegistry);
    }

    @Override
    public void recordNegativeBalanceAlert(UUID userId, long negativeAmount) {
        log.warn("METRIC: billing.balance.negative_alert userId={} negativeAmount={}", userId, negativeAmount);
        Counter.builder("billing.balance.negative_alert")
                .description("Number of negative balance alerts")
                .tag("userId", userId.toString())
                .register(meterRegistry)
                .increment();
    }
}
