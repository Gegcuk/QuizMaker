package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

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
                    "spring.ai.openai.chat.options.temperature=1.0",
                    "spring.ai.openai.chat.options.max-completion-tokens=" + DEFAULT_MAX_COMPLETION_TOKENS,
                    "spring.ai.openai.embedding.enabled=false",
                    "spring.ai.openai.image.enabled=false",
                    "spring.ai.openai.audio.transcription.enabled=false",
                    "spring.ai.openai.audio.speech.enabled=false"
            );

    @Test
    @DisplayName("Binds Luna-compatible model defaults and the modern token limit")
    void bindsLunaCompatibleDefaultsAndModernTokenLimit() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            OpenAiChatOptions modelOptions = (OpenAiChatOptions) context
                    .getBean(OpenAiChatModel.class)
                    .getDefaultOptions();
            SpringAiStructuredClient structuredClient = context.getBean(SpringAiStructuredClient.class);

            assertThat(modelOptions.getModel()).isEqualTo("gpt-5.6-luna");
            assertThat(modelOptions.getTemperature()).isEqualTo(1.0);
            assertThat(modelOptions.getMaxCompletionTokens()).isEqualTo(DEFAULT_MAX_COMPLETION_TOKENS);
            assertThat(modelOptions.getMaxTokens()).isNull();
            assertThat(modelOptions.getTopP()).isNull();
            assertThat(modelOptions.getFrequencyPenalty()).isNull();
            assertThat(modelOptions.getPresencePenalty()).isNull();
            assertThat(modelOptions.getStop()).isNull();
            assertThat(modelOptions.getSeed()).isNull();
            assertThat(ReflectionTestUtils.getField(structuredClient, "maxCompletionTokens"))
                    .isEqualTo(DEFAULT_MAX_COMPLETION_TOKENS);
        });
    }

    @Test
    @DisplayName("Produces a Luna-compatible structured Chat Completions request")
    void producesLunaCompatibleStructuredChatCompletionsRequest() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            OpenAiChatModel chatModel = context.getBean(OpenAiChatModel.class);
            SpringAiStructuredClient structuredClient = context.getBean(SpringAiStructuredClient.class);
            QuestionSchemaRegistry schemaRegistry = context.getBean(QuestionSchemaRegistry.class);
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            JsonNode schema = schemaRegistry.getSchemaForQuestionTypeAi(
                    QuestionType.MATCHING,
                    Difficulty.MEDIUM
            );
            OpenAiChatOptions runtimeOptions = ReflectionTestUtils.invokeMethod(
                    structuredClient,
                    "buildChatOptions",
                    QuestionType.MATCHING,
                    schema
            );
            Prompt runtimePrompt = new Prompt(List.of(
                    new SystemMessage("Generate questions."),
                    new UserMessage("Use the supplied content.")
            ), runtimeOptions);

            Prompt mergedPrompt = ReflectionTestUtils.invokeMethod(
                    chatModel,
                    "buildRequestPrompt",
                    runtimePrompt
            );
            OpenAiApi.ChatCompletionRequest request = ReflectionTestUtils.invokeMethod(
                    chatModel,
                    "createRequest",
                    mergedPrompt,
                    false
            );
            JsonNode requestJson = objectMapper.valueToTree(request);

            assertThat(requestJson.path("model").asText()).isEqualTo("gpt-5.6-luna");
            assertThat(requestJson.path("temperature").asDouble()).isEqualTo(1.0);
            assertThat(requestJson.path("max_completion_tokens").asInt())
                    .isEqualTo(DEFAULT_MAX_COMPLETION_TOKENS);
            assertThat(requestJson.path("stream").asBoolean()).isFalse();
            assertThat(requestJson.path("response_format").path("type").asText())
                    .isEqualTo("json_schema");
            assertThat(requestJson.path("response_format").path("json_schema").path("strict").asBoolean())
                    .isTrue();

            List<String> requestFields = new ArrayList<>();
            requestJson.fieldNames().forEachRemaining(requestFields::add);
            assertThat(requestFields).containsExactlyInAnyOrder(
                    "messages",
                    "model",
                    "max_completion_tokens",
                    "response_format",
                    "stream",
                    "temperature"
            );

            assertThat(requestJson.has("max_tokens")).isFalse();
            assertThat(requestJson.has("top_p")).isFalse();
            assertThat(requestJson.has("frequency_penalty")).isFalse();
            assertThat(requestJson.has("presence_penalty")).isFalse();
            assertThat(requestJson.has("stop")).isFalse();
            assertThat(requestJson.has("seed")).isFalse();
            assertThat(requestJson.has("logit_bias")).isFalse();
            assertThat(requestJson.has("logprobs")).isFalse();
            assertThat(requestJson.has("top_logprobs")).isFalse();
            assertThat(requestJson.has("reasoning_effort")).isFalse();
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
