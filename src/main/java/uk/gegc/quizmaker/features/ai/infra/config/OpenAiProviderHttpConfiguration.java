package uk.gegc.quizmaker.features.ai.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResponseErrorHandler;
import uk.gegc.quizmaker.features.ai.infra.provider.OpenAiProviderResponseErrorHandler;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class OpenAiProviderHttpConfiguration {

    @Bean
    public ResponseErrorHandler openAiProviderResponseErrorHandler(
            ObjectMapper objectMapper,
            Clock clock
    ) {
        return new OpenAiProviderResponseErrorHandler(objectMapper, clock);
    }
}
