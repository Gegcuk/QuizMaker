package uk.gegc.quizmaker.features.document.application.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@DisplayName("Safe document ingestion metrics boundary")
class SafeDocumentIngestionMetricsTest {

    @Test
    @DisplayName("Exposes the failure-safe adapter as the primary application metrics boundary")
    void exposesSafeAdapterAsPrimaryBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(MicrometerDocumentIngestionMetrics.class, SafeDocumentIngestionMetrics.class);
            context.refresh();

            assertThat(context.getBean(DocumentIngestionMetrics.class))
                    .isInstanceOf(SafeDocumentIngestionMetrics.class);
        }
    }

    @Test
    @DisplayName("Contains telemetry failures without logging private exception details")
    void containsMetricFailureWithoutLeakingItsMessage() {
        String privateCanary = "PRIVATE_DOCUMENT_HEADING_722";
        MicrometerDocumentIngestionMetrics delegate = mock(MicrometerDocumentIngestionMetrics.class);
        doThrow(new IllegalStateException(privateCanary)).when(delegate).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);
        SafeDocumentIngestionMetrics metrics = new SafeDocumentIngestionMetrics(delegate);
        Logger logger = (Logger) LoggerFactory.getLogger(SafeDocumentIngestionMetrics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> metrics.recordEvent(
                    DocumentIngestionMetrics.Stage.PROCESSING,
                    DocumentIngestionMetrics.Outcome.SUCCEEDED,
                    DocumentIngestionMetrics.Reason.NONE))
                    .doesNotThrowAnyException();
            assertThatCode(() -> metrics.recordEvent(
                    DocumentIngestionMetrics.Stage.PROCESSING,
                    DocumentIngestionMetrics.Outcome.SUCCEEDED,
                    DocumentIngestionMetrics.Reason.NONE))
                    .doesNotThrowAnyException();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Could not record a document ingestion metric")
                    .noneMatch(message -> message.contains(privateCanary));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
