package uk.gegc.quizmaker.features.documentProcess.application.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Normalized document access metrics")
class MicrometerNormalizedDocumentAccessMetricsTest {

    @Test
    @DisplayName("publishes only bounded outcome tags without document or user identifiers")
    void publishesBoundedOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerNormalizedDocumentAccessMetrics metrics = new MicrometerNormalizedDocumentAccessMetrics(registry);

        for (NormalizedDocumentAccessMetrics.Outcome outcome : NormalizedDocumentAccessMetrics.Outcome.values()) {
            metrics.record(outcome);

            assertThat(registry.get(MicrometerNormalizedDocumentAccessMetrics.METER_NAME)
                    .tag("outcome", outcome.tagValue())
                    .counter()
                    .count()).isEqualTo(1.0);
        }

        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactly("outcome")
        );
    }
}
