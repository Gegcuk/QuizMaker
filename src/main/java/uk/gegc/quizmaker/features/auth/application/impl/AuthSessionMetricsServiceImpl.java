package uk.gegc.quizmaker.features.auth.application.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRejectionReason;

@Service
@RequiredArgsConstructor
public class AuthSessionMetricsServiceImpl implements AuthSessionMetricsService {

    private final MeterRegistry meterRegistry;

    @Override
    public void recordSessionIssued() {
        counter("auth.sessions.issued").increment();
    }

    @Override
    public void recordRefreshSucceeded() {
        counter("auth.sessions.refresh.succeeded").increment();
    }

    @Override
    public void recordLogoutSucceeded() {
        counter("auth.sessions.logout.succeeded").increment();
    }

    @Override
    public void recordAccessRejected(AuthSessionRejectionReason reason) {
        rejectionCounter("access", reason).increment();
    }

    @Override
    public void recordRefreshRejected(AuthSessionRejectionReason reason) {
        rejectionCounter("refresh", reason).increment();
    }

    @Override
    public void recordSessionStoreFailure() {
        counter("auth.sessions.store.failures").increment();
    }

    @Override
    public void recordExpiredSessionsPurged(int count) {
        if (count > 0) {
            counter("auth.sessions.expired.purged").increment(count);
        }
    }

    private Counter counter(String name) {
        return Counter.builder(name)
                .description("Authentication session lifecycle events")
                .register(meterRegistry);
    }

    private Counter rejectionCounter(String operation, AuthSessionRejectionReason reason) {
        return Counter.builder("auth.sessions.rejected")
                .description("Rejected authentication session operations")
                .tag("operation", operation)
                .tag("reason", reason.metricValue())
                .register(meterRegistry);
    }
}
