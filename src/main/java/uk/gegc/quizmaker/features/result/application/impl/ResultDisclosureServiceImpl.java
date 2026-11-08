package uk.gegc.quizmaker.features.result.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.result.application.ResultDisclosureService;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;

@Service
public class ResultDisclosureServiceImpl implements ResultDisclosureService {
    private final MeterRegistry metrics;

    public ResultDisclosureServiceImpl(MeterRegistry metrics) { this.metrics = metrics; }

    @Override public ResultDisclosure.Decisions evaluate(ResultDisclosure.Context context) {
        var decisions = ResultDisclosure.evaluate(context);
        String version = context != null && context.version() == ResultDisclosure.VERSION ? "1" : "unsupported";
        decisions.categories().forEach((category, decision) -> {
            try {
                metrics.counter("quizmaker.result.disclosure", "version", version,
                        "category", category.name().toLowerCase(Locale.ROOT),
                        "outcome", decision.outcome().name().toLowerCase(Locale.ROOT)).increment();
            } catch (RuntimeException ignored) { /* telemetry cannot change disclosure */ }
        });
        return decisions;
    }
}
