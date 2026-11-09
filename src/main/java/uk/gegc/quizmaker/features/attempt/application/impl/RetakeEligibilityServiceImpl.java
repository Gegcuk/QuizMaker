package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.RetakeEligibilityService;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;

@Service
public final class RetakeEligibilityServiceImpl implements RetakeEligibilityService {
    private final Clock clock;
    private final MeterRegistry metrics;

    public RetakeEligibilityServiceImpl(Clock clock, MeterRegistry metrics) {
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public Decision evaluate(Context context) {
        Instant now = null;
        try { now = clock.instant(); }
        catch (RuntimeException unavailable) { /* Never substitute client time or expose clock diagnostics. */ }
        var decision = RetakeEligibility.evaluate(context, now);
        try {
            metrics.counter("quizmaker.attempt.retake.eligibility",
                    "version", context != null && context.version() == RetakeEligibility.VERSION ? "1" : "unsupported",
                    "outcome", decision.outcome().name().toLowerCase(Locale.ROOT),
                    "reason", decision.reason().name().toLowerCase(Locale.ROOT)).increment();
        } catch (RuntimeException unavailable) { /* Telemetry cannot alter eligibility. */ }
        return decision;
    }
}
