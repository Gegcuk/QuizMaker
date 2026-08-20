package uk.gegc.quizmaker.features.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth code exchange property binding")
class OAuth2ExchangePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertyConfiguration.class, ValidatorConfiguration.class)
            .withPropertyValues(
                    "app.oauth2.exchange.clients.quizzence-web.redirect-uri=http://localhost:3000/oauth2/redirect"
            );

    @Test
    @DisplayName("an absent compatibility cutoff is secure-only and keeps the two-minute code lifetime")
    void absentLegacyCutoff_bindsAsSecureOnly() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            OAuth2ExchangeProperties properties = context.getBean(OAuth2ExchangeProperties.class);

            assertThat(properties.getExchange().getCodeTtl()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.getExchange().getLegacyTokenRedirectUntil()).isNull();
            assertThat(properties.getExchange().getClients())
                    .containsKey("quizzence-web");
        });
    }

    @Test
    @DisplayName("an explicitly empty deployment cutoff also binds as secure-only")
    void emptyLegacyCutoff_bindsAsSecureOnly() {
        contextRunner.withPropertyValues(
                        "app.oauth2.exchange.legacy-token-redirect-until="
                )
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    OAuth2ExchangeProperties properties = context.getBean(OAuth2ExchangeProperties.class);

                    assertThat(properties.getExchange().getLegacyTokenRedirectUntil()).isNull();
                });
    }

    @Test
    @DisplayName("an unsafe compatibility window fails application startup")
    void farFutureLegacyCutoff_failsStartup() {
        contextRunner.withPropertyValues(
                        "app.oauth2.exchange.legacy-token-redirect-until=2026-08-28T12:00:00Z"
                )
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Legacy OAuth token redirects cannot be enabled for more than seven days"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OAuth2ExchangeProperties.class)
    static class PropertyConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class ValidatorConfiguration {

        @Bean("utcClock")
        Clock utcClock() {
            return Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        OAuth2ExchangeConfigurationValidator oauth2ExchangeConfigurationValidator(
                OAuth2ExchangeProperties properties,
                Clock utcClock
        ) {
            return new OAuth2ExchangeConfigurationValidator(properties, utcClock);
        }
    }
}
