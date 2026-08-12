package uk.gegc.quizmaker.features.document.application.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MicrometerDocumentIngestionMetrics implements DocumentIngestionMetrics {

    static final String EVENTS_METER = "document.ingestion.events";
    static final String DURATION_METER = "document.ingestion.duration";
    static final String ACTIVE_METER = "document.ingestion.active";
    static final String EXTRACTED_CHARACTERS_METER = "document.ingestion.extracted.characters";
    static final String PAGES_METER = "document.ingestion.pages";
    static final String RECONCILIATION_CANDIDATES_METER = "document.storage.reconciliation.candidates";

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeIngestions = new AtomicInteger();
    private final ConcurrentMap<EventKey, Counter> eventCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<DurationKey, Timer> durationTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Format, DistributionSummary> extractedCharacters = new ConcurrentHashMap<>();
    private final ConcurrentMap<Format, DistributionSummary> extractedPages = new ConcurrentHashMap<>();
    private final DistributionSummary reconciliationCandidates;

    public MicrometerDocumentIngestionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder(ACTIVE_METER, activeIngestions, AtomicInteger::get)
                .description("Document ingestion requests currently being processed")
                .register(meterRegistry);
        reconciliationCandidates = DistributionSummary.builder(RECONCILIATION_CANDIDATES_METER)
                .description("Expired published files considered by one reconciliation run")
                .baseUnit("files")
                .serviceLevelObjectives(1, 10, 100, 250)
                .register(meterRegistry);
    }

    @Override
    public void ingestionStarted() {
        activeIngestions.incrementAndGet();
    }

    @Override
    public void ingestionStopped() {
        activeIngestions.updateAndGet(current -> Math.max(0, current - 1));
    }

    @Override
    public void recordEvent(Stage stage, Outcome outcome, Reason reason) {
        EventKey key = new EventKey(
                Objects.requireNonNull(stage, "Document metric stage is required"),
                Objects.requireNonNull(outcome, "Document metric outcome is required"),
                Objects.requireNonNull(reason, "Document metric reason is required")
        );
        eventCounters.computeIfAbsent(key, this::createEventCounter).increment();
    }

    @Override
    public void recordDuration(Stage stage, Outcome outcome, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        DurationKey key = new DurationKey(
                Objects.requireNonNull(stage, "Document metric stage is required"),
                Objects.requireNonNull(outcome, "Document metric outcome is required")
        );
        durationTimers.computeIfAbsent(key, this::createDurationTimer).record(duration);
    }

    @Override
    public void recordExtracted(Format format, int characters, Integer pages) {
        Format boundedFormat = Objects.requireNonNull(format, "Document metric format is required");
        if (characters >= 0) {
            extractedCharacters.computeIfAbsent(boundedFormat, this::createExtractedCharactersSummary)
                    .record(characters);
        }
        if (pages != null && pages > 0) {
            extractedPages.computeIfAbsent(boundedFormat, this::createPagesSummary).record(pages);
        }
    }

    @Override
    public void recordReconciliationCandidates(int count) {
        reconciliationCandidates.record(Math.max(0, count));
    }

    private Counter createEventCounter(EventKey key) {
        return Counter.builder(EVENTS_METER)
                .description("Document ingestion and storage-maintenance outcomes")
                .tag("stage", key.stage().tagValue())
                .tag("outcome", key.outcome().tagValue())
                .tag("reason", key.reason().tagValue())
                .register(meterRegistry);
    }

    private Timer createDurationTimer(DurationKey key) {
        return Timer.builder(DURATION_METER)
                .description("Document ingestion stage duration")
                .tag("stage", key.stage().tagValue())
                .tag("outcome", key.outcome().tagValue())
                .serviceLevelObjectives(
                        Duration.ofMillis(100),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(1)
                )
                .register(meterRegistry);
    }

    private DistributionSummary createExtractedCharactersSummary(Format format) {
        return DistributionSummary.builder(EXTRACTED_CHARACTERS_METER)
                .description("Extracted document characters by bounded format")
                .baseUnit("characters")
                .tag("format", format.tagValue())
                .serviceLevelObjectives(10_000, 100_000, 1_000_000, 5_000_000)
                .register(meterRegistry);
    }

    private DistributionSummary createPagesSummary(Format format) {
        return DistributionSummary.builder(PAGES_METER)
                .description("Extracted document pages by bounded format")
                .baseUnit("pages")
                .tag("format", format.tagValue())
                .serviceLevelObjectives(10, 50, 100, 500, 2_000)
                .register(meterRegistry);
    }

    private record EventKey(Stage stage, Outcome outcome, Reason reason) {
    }

    private record DurationKey(Stage stage, Outcome outcome) {
    }
}
