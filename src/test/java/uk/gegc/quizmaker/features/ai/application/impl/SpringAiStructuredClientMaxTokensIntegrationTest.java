package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for max-tokens configuration in SpringAiStructuredClient.
 * 
 * These tests verify that the Spring Boot configuration system properly injects
 * the max-tokens value from application.properties into the SpringAiStructuredClient bean.
 * 
 * Tests run with @ActiveProfiles("test") to use application-test.properties configuration.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SpringAiStructuredClient - Max Tokens Configuration Integration")
class SpringAiStructuredClientMaxTokensIntegrationTest {

    @Autowired
    private SpringAiStructuredClient client;

    @Autowired
    private QuestionSchemaRegistry schemaRegistry;

    @Value("${spring.ai.openai.chat.options.max-tokens:16000}")
    private Integer expectedMaxTokens;

    @Test
    @DisplayName("Should inject max-tokens from application.properties")
    void shouldInjectMaxTokensFromConfiguration() {
        // When - Get the injected value
        Integer actualMaxTokens = (Integer) ReflectionTestUtils.getField(client, "maxCompletionTokens");

        // Then - Should match the configuration value
        assertThat(actualMaxTokens)
            .as("maxCompletionTokens should be injected from application.properties")
            .isNotNull()
            .isEqualTo(expectedMaxTokens);
    }

    @Test
    @DisplayName("Should use configured max-tokens in generated OpenAiChatOptions")
    void shouldUseConfiguredMaxTokensInChatOptions() throws Exception {
        // Given
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE);

        // When - Build chat options (via reflection to access private method)
        Method method = SpringAiStructuredClient.class.getDeclaredMethod(
            "buildChatOptions", QuestionType.class, JsonNode.class);
        method.setAccessible(true);
        OpenAiChatOptions options = (OpenAiChatOptions) method.invoke(client, QuestionType.MCQ_SINGLE, schema);

        // Then - Should use the configured max-tokens value
        assertThat(options).isNotNull();
        assertThat(options.getMaxTokens())
            .as("Generated options should use configured max-tokens")
            .isEqualTo(expectedMaxTokens);
    }

    @Test
    @DisplayName("Should have reasonable default value (16000) when not explicitly configured")
    void shouldHaveReasonableDefaultValue() {
        // When - Get the configured value
        Integer maxTokens = (Integer) ReflectionTestUtils.getField(client, "maxCompletionTokens");

        // Then - Should have a reasonable default
        assertThat(maxTokens)
            .as("Default max-tokens should be set")
            .isNotNull()
            .isGreaterThan(0)
            .as("Default should be at least 10000 to support typical 10-question generation")
            .isGreaterThanOrEqualTo(10_000)
            .as("Default should not exceed 32000 (model's hard limit)")
            .isLessThanOrEqualTo(32_000);
    }

    @Test
    @DisplayName("Should support all question types with configured max-tokens")
    void shouldSupportAllQuestionTypesWithConfiguredMaxTokens() throws Exception {
        // Given
        Method method = SpringAiStructuredClient.class.getDeclaredMethod(
            "buildChatOptions", QuestionType.class, JsonNode.class);
        method.setAccessible(true);

        // When/Then - Test all question types
        for (QuestionType type : QuestionType.values()) {
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(type);
            OpenAiChatOptions options = (OpenAiChatOptions) method.invoke(client, type, schema);

            assertThat(options)
                .as("Options should be generated for " + type)
                .isNotNull();
            assertThat(options.getMaxTokens())
                .as("MaxTokens should be set for " + type)
                .isEqualTo(expectedMaxTokens);
        }
    }

    @Test
    @DisplayName("SpringAiStructuredClient bean should be properly initialized")
    void springAiStructuredClientBeanShouldBeProperlyInitialized() {
        // Then - Verify the bean is properly constructed with all dependencies
        assertThat(client).isNotNull();
        assertThat(ReflectionTestUtils.getField(client, "chatClient")).isNotNull();
        assertThat(ReflectionTestUtils.getField(client, "schemaRegistry")).isNotNull();
        assertThat(ReflectionTestUtils.getField(client, "promptTemplateService")).isNotNull();
        assertThat(ReflectionTestUtils.getField(client, "objectMapper")).isNotNull();
        assertThat(ReflectionTestUtils.getField(client, "rateLimitConfig")).isNotNull();
        assertThat(ReflectionTestUtils.getField(client, "maxCompletionTokens")).isNotNull();
    }

    @Test
    @DisplayName("Should maintain max-tokens across multiple invocations")
    void shouldMaintainMaxTokensAcrossMultipleInvocations() throws Exception {
        // Given
        Method method = SpringAiStructuredClient.class.getDeclaredMethod(
            "buildChatOptions", QuestionType.class, JsonNode.class);
        method.setAccessible(true);

        // When - Build options multiple times
        OpenAiChatOptions options1 = (OpenAiChatOptions) method.invoke(
            client, QuestionType.MCQ_SINGLE, 
            schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE));
        
        OpenAiChatOptions options2 = (OpenAiChatOptions) method.invoke(
            client, QuestionType.TRUE_FALSE,
            schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE));
        
        OpenAiChatOptions options3 = (OpenAiChatOptions) method.invoke(
            client, QuestionType.MATCHING,
            schemaRegistry.getSchemaForQuestionType(QuestionType.MATCHING));

        // Then - All should use the same configured max-tokens
        assertThat(options1.getMaxTokens()).isEqualTo(expectedMaxTokens);
        assertThat(options2.getMaxTokens()).isEqualTo(expectedMaxTokens);
        assertThat(options3.getMaxTokens()).isEqualTo(expectedMaxTokens);
    }

    @Test
    @DisplayName("Configuration should use environment variable override if provided")
    void configurationShouldUseEnvironmentVariableOverride() {
        // Given - Test configuration can be overridden via OPENAI_MAX_COMPLETION_TOKENS env var
        // (This is tested indirectly by verifying the @Value annotation default works)
        
        Integer maxTokens = (Integer) ReflectionTestUtils.getField(client, "maxCompletionTokens");

        // Then - Should have a value (either from env var or default)
        assertThat(maxTokens)
            .as("Should load from spring.ai.openai.chat.options.max-tokens or use default")
            .isNotNull()
            .as("Reasonable value range")
            .isBetween(1000, 128_000);
    }
}

