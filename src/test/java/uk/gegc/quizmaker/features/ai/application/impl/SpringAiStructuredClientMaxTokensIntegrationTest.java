package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal Spring configuration tests for OpenAI completion-token options.
 */
@DisplayName("Spring AI max completion tokens configuration")
class SpringAiStructuredClientMaxTokensIntegrationTest {

    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 16_000;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenAiAutoConfiguration.class))
            .withUserConfiguration(StructuredClientTestConfiguration.class)
            .withPropertyValues(
                    "spring.ai.openai.api-key=test-key",
                    "spring.ai.openai.chat.options.model=gpt-5.6-luna",
                    "spring.ai.openai.chat.options.max-completion-tokens=" + DEFAULT_MAX_COMPLETION_TOKENS,
                    "spring.ai.openai.embedding.enabled=false",
                    "spring.ai.openai.image.enabled=false",
                    "spring.ai.openai.audio.transcription.enabled=false",
                    "spring.ai.openai.audio.speech.enabled=false"
            );

    @Test
    @DisplayName("Binds the modern token limit to both model and structured client")
    void bindsModernTokenLimitToModelAndStructuredClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            OpenAiChatOptions modelOptions = (OpenAiChatOptions) context
                    .getBean(OpenAiChatModel.class)
                    .getDefaultOptions();
            SpringAiStructuredClient structuredClient = context.getBean(SpringAiStructuredClient.class);

            assertThat(modelOptions.getMaxCompletionTokens()).isEqualTo(DEFAULT_MAX_COMPLETION_TOKENS);
            assertThat(modelOptions.getMaxTokens()).isNull();
            assertThat(ReflectionTestUtils.getField(structuredClient, "maxCompletionTokens"))
                    .isEqualTo(DEFAULT_MAX_COMPLETION_TOKENS);
        });
    }

    @Test
    @DisplayName("Accepts an overridden completion token limit")
    void acceptsOverriddenCompletionTokenLimit() {
        contextRunner
                .withPropertyValues("spring.ai.openai.chat.options.max-completion-tokens=24000")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    OpenAiChatOptions modelOptions = (OpenAiChatOptions) context
                            .getBean(OpenAiChatModel.class)
                            .getDefaultOptions();
                    SpringAiStructuredClient structuredClient = context.getBean(SpringAiStructuredClient.class);

                    assertThat(modelOptions.getMaxCompletionTokens()).isEqualTo(24_000);
                    assertThat(modelOptions.getMaxTokens()).isNull();
                    assertThat(ReflectionTestUtils.getField(structuredClient, "maxCompletionTokens"))
                            .isEqualTo(24_000);
                });
    }

    @Test
    @DisplayName("Starts AI option wiring without database infrastructure")
    void startsWithoutDatabaseInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpenAiChatModel.class);
            assertThat(context).hasSingleBean(SpringAiStructuredClient.class);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StructuredClientTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        QuestionSchemaRegistry questionSchemaRegistry(ObjectMapper objectMapper) {
            return new QuestionSchemaRegistry(objectMapper);
        }

        @Bean
        PromptTemplateService promptTemplateService() {
            return new PromptTemplateServiceImpl(new DefaultResourceLoader());
        }

        @Bean
        AiRateLimitConfig aiRateLimitConfig() {
            return new AiRateLimitConfig();
        }

        @Bean
        SpringAiStructuredClient springAiStructuredClient(
                OpenAiChatModel chatModel,
                QuestionSchemaRegistry schemaRegistry,
                PromptTemplateService promptTemplateService,
                ObjectMapper objectMapper,
                AiRateLimitConfig rateLimitConfig
        ) {
            return new SpringAiStructuredClient(
                    ChatClient.create(chatModel),
                    schemaRegistry,
                    promptTemplateService,
                    objectMapper,
                    rateLimitConfig
            );
        }
    }
}
