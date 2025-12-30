package uk.gegc.quizmaker.shared.seo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SeoProperties.class)
public class SeoConfig {
}
