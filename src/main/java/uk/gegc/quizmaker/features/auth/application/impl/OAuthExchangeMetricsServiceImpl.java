package uk.gegc.quizmaker.features.auth.application.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeMetricsService;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeRejectionReason;

@Service
@RequiredArgsConstructor
public class OAuthExchangeMetricsServiceImpl implements OAuthExchangeMetricsService {

    private final MeterRegistry meterRegistry;

    @Override
    public void recordCodeIssued() {
        outcome("code_issued").increment();
    }

    @Override
    public void recordExchangeSucceeded() {
        outcome("succeeded").increment();
    }

    @Override
    public void recordExchangeRejected(OAuthExchangeRejectionReason reason) {
        Counter.builder("auth.oauth.exchange.rejected")
                .description("Rejected OAuth one-time-code exchanges")
                .tag("reason", reason.metricValue())
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordLegacyRedirect() {
        outcome("legacy_redirect").increment();
    }

    @Override
    public void recordStoreFailure() {
        outcome("store_failure").increment();
    }

    @Override
    public void recordExpiredCodesPurged(int count) {
        if (count > 0) {
            outcome("expired_purged").increment(count);
        }
    }

    private Counter outcome(String outcome) {
        return Counter.builder("auth.oauth.exchange.outcomes")
                .description("OAuth one-time-code lifecycle outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
