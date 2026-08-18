package uk.gegc.quizmaker.features.documentProcess.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gegc.quizmaker.features.documentProcess.api.NormalizedDocumentAuthenticationMetricsFilter;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Normalized document access metrics configuration")
class NormalizedDocumentAccessMetricsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(NormalizedDocumentAccessMetricsConfiguration.class);

    @Test
    @DisplayName("registers the authentication filter when access metrics are available")
    void registersFilterWithMetrics() {
        contextRunner
                .withBean(NormalizedDocumentAccessMetrics.class, () -> outcome -> { })
                .run(context -> assertThat(context)
                        .hasSingleBean(NormalizedDocumentAuthenticationMetricsFilter.class));
    }
}
