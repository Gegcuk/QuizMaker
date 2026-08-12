package uk.gegc.quizmaker.features.document.application.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerMetrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MicrometerDocumentParserWorkerMetrics implements DocumentParserWorkerMetrics {

    static final String EVENTS_METER = "document.parser.worker.events";
    static final String ACTIVE_METER = "document.parser.workers.active";

    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final Map<Outcome, Counter> outcomeCounters = new EnumMap<>(Outcome.class);

    public MicrometerDocumentParserWorkerMetrics(MeterRegistry meterRegistry) {
        Gauge.builder(ACTIVE_METER, activeWorkers, AtomicInteger::get)
                .description("Currently running isolated document parser workers")
                .register(meterRegistry);
        for (Outcome outcome : Outcome.values()) {
            outcomeCounters.put(outcome, Counter.builder(EVENTS_METER)
                    .description("Isolated document parser lifecycle events")
                    .tag("outcome", outcome.tagValue())
                    .register(meterRegistry));
        }
    }

    @Override
    public void workerStarted() {
        activeWorkers.incrementAndGet();
    }

    @Override
    public void workerStopped() {
        activeWorkers.updateAndGet(current -> Math.max(0, current - 1));
    }

    @Override
    public void record(Outcome outcome) {
        outcomeCounters.get(outcome).increment();
    }
}
