package uk.gegc.quizmaker.features.documentProcess.application.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;

import java.util.EnumMap;
import java.util.Map;

@Component
public class MicrometerNormalizedDocumentAccessMetrics implements NormalizedDocumentAccessMetrics {

    static final String METER_NAME = "document.normalized.access";

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public MicrometerNormalizedDocumentAccessMetrics(MeterRegistry meterRegistry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder(METER_NAME)
                    .description("Owner-scoped normalized-document access outcomes")
                    .tag("outcome", outcome.tagValue())
                    .register(meterRegistry));
        }
    }

    @Override
    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }
}
