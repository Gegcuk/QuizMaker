package uk.gegc.quizmaker.features.document.application.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps observability failures outside the document-processing result path. */
@Component
@Primary
@Slf4j
public class SafeDocumentIngestionMetrics implements DocumentIngestionMetrics {

    private final MicrometerDocumentIngestionMetrics delegate;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public SafeDocumentIngestionMetrics(MicrometerDocumentIngestionMetrics delegate) {
        this.delegate = delegate;
    }

    @Override
    public void ingestionStarted() {
        safely(delegate::ingestionStarted);
    }

    @Override
    public void ingestionStopped() {
        safely(delegate::ingestionStopped);
    }

    @Override
    public void recordEvent(Stage stage, Outcome outcome, Reason reason) {
        safely(() -> delegate.recordEvent(stage, outcome, reason));
    }

    @Override
    public void recordDuration(Stage stage, Outcome outcome, Duration duration) {
        safely(() -> delegate.recordDuration(stage, outcome, duration));
    }

    @Override
    public void recordExtracted(Format format, int characters, Integer pages) {
        safely(() -> delegate.recordExtracted(format, characters, pages));
    }

    @Override
    public void recordReconciliationCandidates(int count) {
        safely(() -> delegate.recordReconciliationCandidates(count));
    }

    private void safely(Runnable metricOperation) {
        try {
            metricOperation.run();
        } catch (RuntimeException ignored) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Could not record a document ingestion metric");
            }
        }
    }
}
