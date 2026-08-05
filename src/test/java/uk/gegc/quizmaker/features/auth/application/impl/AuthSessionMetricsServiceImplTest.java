package uk.gegc.quizmaker.features.auth.application.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRejectionReason;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Authentication Session Metrics")
class AuthSessionMetricsServiceImplTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuthSessionMetricsServiceImpl metrics = new AuthSessionMetricsServiceImpl(meterRegistry);

    @AfterEach
    void cleanUp() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("records only fixed-cardinality lifecycle and rejection metrics")
    void recordsBoundedLifecycleAndRejectionMetrics() {
        metrics.recordSessionIssued();
        metrics.recordRefreshSucceeded();
        metrics.recordLogoutSucceeded();
        metrics.recordAccessRejected(AuthSessionRejectionReason.INVALID_TOKEN);
        metrics.recordRefreshRejected(AuthSessionRejectionReason.REPLAYED_TOKEN);
        metrics.recordSessionStoreFailure();
        metrics.recordExpiredSessionsPurged(3);
        metrics.recordExpiredSessionsPurged(0);

        assertThat(meterRegistry.get("auth.sessions.issued").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("auth.sessions.refresh.succeeded").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("auth.sessions.logout.succeeded").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("auth.sessions.rejected")
                .tag("operation", "access")
                .tag("reason", "invalid_token")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("auth.sessions.rejected")
                .tag("operation", "refresh")
                .tag("reason", "replayed_token")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("auth.sessions.store.failures").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("auth.sessions.expired.purged").counter().count()).isEqualTo(3.0);
    }
}
