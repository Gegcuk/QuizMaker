package uk.gegc.quizmaker.features.ai.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.autoconfigure.retry.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResponseErrorHandler;
import uk.gegc.quizmaker.features.ai.infra.provider.OpenAiProviderResponseErrorHandler;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OpenAI provider HTTP configuration")
class OpenAiProviderHttpConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    SpringAiRetryAutoConfiguration.class
            ))
            .withUserConfiguration(
                    OpenAiProviderHttpConfiguration.class,
                    FixedClockConfiguration.class
            );

    @Test
    @DisplayName("Selects the project handler instead of Spring AI's default handler")
    void projectHandlerOwnsProviderErrorClassification() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ResponseErrorHandler.class);
            assertThat(context.getBean(ResponseErrorHandler.class))
                    .isInstanceOf(OpenAiProviderResponseErrorHandler.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
