package uk.gegc.quizmaker.features.documentProcess.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.features.documentProcess.api.NormalizedDocumentAuthenticationMetricsFilter;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;

@Configuration(proxyBeanMethods = false)
public class NormalizedDocumentAccessMetricsConfiguration {

    @Bean
    NormalizedDocumentAuthenticationMetricsFilter normalizedDocumentAuthenticationMetricsFilter(
            NormalizedDocumentAccessMetrics metrics
    ) {
        return new NormalizedDocumentAuthenticationMetricsFilter(metrics);
    }
}
