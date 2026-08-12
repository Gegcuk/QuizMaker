package uk.gegc.quizmaker.features.document.application.impl;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingTimeoutException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentStorageException;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document ingestion Micrometer adapter")
class MicrometerDocumentIngestionMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MicrometerDocumentIngestionMetrics metrics =
            new MicrometerDocumentIngestionMetrics(meterRegistry);

    @AfterEach
    void closeRegistry() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("Records lifecycle, duration, extraction, and reconciliation values with fixed tags")
    void recordsBoundedDocumentMetrics() {
        metrics.ingestionStarted();
        metrics.ingestionStarted();
        metrics.ingestionStopped();
        metrics.recordEvent(
                DocumentIngestionMetrics.Stage.STAGING,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.TYPE_MISMATCH);
        metrics.recordEvent(
                DocumentIngestionMetrics.Stage.COMPENSATION,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);
        metrics.recordDuration(
                DocumentIngestionMetrics.Stage.CONVERSION,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                Duration.ofMillis(250));
        metrics.recordExtracted(DocumentIngestionMetrics.Format.PDF, 42_000, 12);
        metrics.recordExtracted(DocumentIngestionMetrics.Format.TEXT, 1_024, null);
        metrics.recordReconciliationCandidates(17);

        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.ACTIVE_METER)
                .gauge().value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.EVENTS_METER)
                .tag("stage", "staging")
                .tag("outcome", "rejected")
                .tag("reason", "type_mismatch")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.EVENTS_METER)
                .tag("stage", "compensation")
                .tag("outcome", "succeeded")
                .tag("reason", "none")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.DURATION_METER)
                .tag("stage", "conversion")
                .tag("outcome", "succeeded")
                .timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.DURATION_METER)
                .tag("stage", "conversion")
                .tag("outcome", "succeeded")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0d);
        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.EXTRACTED_CHARACTERS_METER)
                .tag("format", "pdf").summary().totalAmount()).isEqualTo(42_000.0d);
        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.PAGES_METER)
                .tag("format", "pdf").summary().totalAmount()).isEqualTo(12.0d);
        assertThat(meterRegistry.find(MicrometerDocumentIngestionMetrics.PAGES_METER)
                .tag("format", "text").summary()).isNull();
        assertThat(meterRegistry.get(MicrometerDocumentIngestionMetrics.RECONCILIATION_CANDIDATES_METER)
                .summary().totalAmount()).isEqualTo(17.0d);

        Set<String> tagKeys = meterRegistry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
        assertThat(tagKeys).isSubsetOf("stage", "outcome", "reason", "format", "le");
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertNoPrivateTags(meter.getId()));
    }

    @Test
    @DisplayName("Publishes every documented stage, outcome, reason, and format as a bounded tag value")
    void publishesEveryDocumentedTagValue() {
        for (DocumentIngestionMetrics.Stage stage : DocumentIngestionMetrics.Stage.values()) {
            metrics.recordEvent(
                    stage,
                    DocumentIngestionMetrics.Outcome.SUCCEEDED,
                    DocumentIngestionMetrics.Reason.NONE);
            assertThat(meterRegistry.find(MicrometerDocumentIngestionMetrics.EVENTS_METER)
                    .tag("stage", stage.tagValue()).counter()).isNotNull();
        }
        for (DocumentIngestionMetrics.Outcome outcome : DocumentIngestionMetrics.Outcome.values()) {
            metrics.recordEvent(
                    DocumentIngestionMetrics.Stage.PROCESSING,
                    outcome,
                    DocumentIngestionMetrics.Reason.NONE);
            assertThat(meterRegistry.find(MicrometerDocumentIngestionMetrics.EVENTS_METER)
                    .tag("outcome", outcome.tagValue()).counter()).isNotNull();
        }
        for (DocumentIngestionMetrics.Reason reason : DocumentIngestionMetrics.Reason.values()) {
            metrics.recordEvent(
                    DocumentIngestionMetrics.Stage.PROCESSING,
                    DocumentIngestionMetrics.Outcome.FAILED,
                    reason);
            assertThat(meterRegistry.find(MicrometerDocumentIngestionMetrics.EVENTS_METER)
                    .tag("reason", reason.tagValue()).counter()).isNotNull();
        }
        for (DocumentIngestionMetrics.Format format : DocumentIngestionMetrics.Format.values()) {
            metrics.recordExtracted(format, 1, 1);
            assertThat(meterRegistry.find(MicrometerDocumentIngestionMetrics.EXTRACTED_CHARACTERS_METER)
                    .tag("format", format.tagValue()).summary()).isNotNull();
        }

        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertNoPrivateTags(meter.getId()));
    }

    @Test
    @DisplayName("Classifies typed failures without inspecting private exception messages")
    void classifiesFailuresByType() {
        assertThat(DocumentIngestionMetrics.Reason.from(new DocumentProcessingTimeoutException()))
                .isEqualTo(DocumentIngestionMetrics.Reason.TIMEOUT);
        assertThat(DocumentIngestionMetrics.Reason.from(
                new DocumentProcessingException(
                        "safe wrapper",
                        new DocumentStorageException("private path omitted"))))
                .isEqualTo(DocumentIngestionMetrics.Reason.STORAGE);
        assertThat(DocumentIngestionMetrics.Reason.from(
                new DocumentResourceLimitException("private parser detail")))
                .isEqualTo(DocumentIngestionMetrics.Reason.RESOURCE_LIMIT);
        assertThat(DocumentIngestionMetrics.Format.fromContentType(" Text/Plain; charset=UTF-8 "))
                .isEqualTo(DocumentIngestionMetrics.Format.TEXT);
    }

    private void assertNoPrivateTags(Meter.Id meterId) {
        assertThat(meterId.getTags())
                .extracting(tag -> tag.getKey().toLowerCase())
                .noneMatch(key -> key.contains("user")
                        || key.contains("document")
                        || key.contains("filename")
                        || key.contains("path")
                        || key.contains("content")
                        || key.contains("id"));
    }
}
