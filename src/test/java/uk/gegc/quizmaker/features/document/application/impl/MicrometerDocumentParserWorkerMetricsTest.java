package uk.gegc.quizmaker.features.document.application.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerMetrics;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document parser worker metrics")
class MicrometerDocumentParserWorkerMetricsTest {

    @Test
    @DisplayName("Tracks active workers without allowing the gauge to become negative")
    void tracksActiveWorkerCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerDocumentParserWorkerMetrics metrics = new MicrometerDocumentParserWorkerMetrics(registry);

        metrics.workerStarted();
        metrics.workerStarted();
        assertThat(registry.get(MicrometerDocumentParserWorkerMetrics.ACTIVE_METER).gauge().value())
                .isEqualTo(2.0);

        metrics.workerStopped();
        metrics.workerStopped();
        metrics.workerStopped();
        assertThat(registry.get(MicrometerDocumentParserWorkerMetrics.ACTIVE_METER).gauge().value())
                .isZero();
    }

    @Test
    @DisplayName("Uses only the fixed outcome vocabulary for lifecycle event tags")
    void recordsOnlyBoundedOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerDocumentParserWorkerMetrics metrics = new MicrometerDocumentParserWorkerMetrics(registry);

        for (DocumentParserWorkerMetrics.Outcome outcome : DocumentParserWorkerMetrics.Outcome.values()) {
            metrics.record(outcome);
        }

        assertThat(registry.find(MicrometerDocumentParserWorkerMetrics.EVENTS_METER).counters())
                .hasSize(DocumentParserWorkerMetrics.Outcome.values().length)
                .allSatisfy(counter -> {
                    assertThat(counter.count()).isEqualTo(1.0);
                    assertThat(counter.getId().getTags()).hasSize(1);
                    assertThat(counter.getId().getTag("outcome")).isNotBlank();
                });
    }
}
