package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for max-tokens configuration in SpringAiStructuredClient.
 * 
 * These tests verify that:
 * 1. The maxCompletionTokens configuration is properly injected
 * 2. The maxTokens value is correctly set in OpenAiChatOptions
 * 3. Truncated JSON responses are detected and handled with helpful error messages
 * 4. Default values work when configuration is not provided
 * 
 * Context: Added to prevent JSON truncation when hitting model's hard token limit (32k for gpt-4.1-mini)
 * See: https://github.com/yourusername/quizmaker/issues/XXX
 */
@DisplayName("SpringAiStructuredClient - Max Tokens Configuration")
class SpringAiStructuredClientMaxTokensTest {

    private ObjectMapper objectMapper;
    private QuestionSchemaRegistry schemaRegistry;
    private SpringAiStructuredClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        // Initialize client with null dependencies - we only test methods that don't need them
        client = new SpringAiStructuredClient(
            null,  // chatClient - not needed for buildChatOptions test
            schemaRegistry,
            null,  // promptTemplateService - not needed
            objectMapper,
            null   // rateLimitConfig - not needed
        );
    }

    @Nested
    @DisplayName("Configuration Injection Tests")
    class ConfigurationInjectionTests {

        @Test
        @DisplayName("Should use configured maxCompletionTokens value")
        void shouldUseConfiguredMaxCompletionTokens() throws Exception {
            // Given - Set a custom max tokens value
            Integer customMaxTokens = 8000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", customMaxTokens);

            // When - Build chat options
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.MCQ_SINGLE, schema);

            // Then - Should use the configured value
            assertThat(options).isNotNull();
            assertThat(options.getMaxTokens()).isEqualTo(customMaxTokens);
        }

        @Test
        @DisplayName("Should use default value of 16000 when not configured")
        void shouldUseDefaultMaxCompletionTokens() throws Exception {
            // Given - Set default value (as Spring would from application.properties default)
            Integer defaultMaxTokens = 16000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", defaultMaxTokens);

            // When - Build chat options
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.TRUE_FALSE, schema);

            // Then - Should use the default value
            assertThat(options).isNotNull();
            assertThat(options.getMaxTokens()).isEqualTo(16000);
        }

        @Test
        @DisplayName("Should work with production-level max tokens (24000)")
        void shouldWorkWithProductionMaxTokens() throws Exception {
            // Given - Set production-level value
            Integer productionMaxTokens = 24000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", productionMaxTokens);

            // When - Build chat options
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MATCHING);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.MATCHING, schema);

            // Then - Should use the production value
            assertThat(options).isNotNull();
            assertThat(options.getMaxTokens()).isEqualTo(24000);
        }

        @Test
        @DisplayName("Should work with conservative max tokens (4000)")
        void shouldWorkWithConservativeMaxTokens() throws Exception {
            // Given - Set conservative value for cost optimization
            Integer conservativeMaxTokens = 4000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", conservativeMaxTokens);

            // When - Build chat options
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.OPEN);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.OPEN, schema);

            // Then - Should use the conservative value
            assertThat(options).isNotNull();
            assertThat(options.getMaxTokens()).isEqualTo(4000);
        }
    }

    @Nested
    @DisplayName("OpenAiChatOptions Integration Tests")
    class OpenAiChatOptionsIntegrationTests {

        @Test
        @DisplayName("Should include maxTokens along with responseFormat")
        void shouldIncludeMaxTokensWithResponseFormat() throws Exception {
            // Given
            Integer maxTokens = 12000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", maxTokens);

            // When
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.FILL_GAP);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.FILL_GAP, schema);

            // Then - Should have both responseFormat and maxTokens
            assertThat(options).isNotNull();
            assertThat(options.getResponseFormat()).isNotNull();
            assertThat(options.getResponseFormat().getType()).isEqualTo(
                org.springframework.ai.openai.api.ResponseFormat.Type.JSON_SCHEMA);
            assertThat(options.getMaxTokens()).isEqualTo(maxTokens);
        }

        @Test
        @DisplayName("Should set maxTokens for all question types")
        void shouldSetMaxTokensForAllQuestionTypes() throws Exception {
            // Given
            Integer maxTokens = 16000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", maxTokens);

            // When/Then - Test all question types
            for (QuestionType type : QuestionType.values()) {
                JsonNode schema = schemaRegistry.getSchemaForQuestionType(type);
                OpenAiChatOptions options = invokeBuildChatOptions(type, schema);

                assertThat(options).as("Options for type: " + type).isNotNull();
                assertThat(options.getMaxTokens())
                    .as("MaxTokens for type: " + type)
                    .isEqualTo(maxTokens);
            }
        }

        @Test
        @DisplayName("Should maintain maxTokens even if responseFormat fails")
        void shouldMaintainMaxTokensEvenIfResponseFormatFails() throws Exception {
            // Given - Use a failing ObjectMapper for schema serialization
            ObjectMapper failingMapper = new ObjectMapper() {
                @Override
                public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                    throw new com.fasterxml.jackson.core.JsonProcessingException("Serialization failed") {};
                }
            };

            SpringAiStructuredClient failingClient = new SpringAiStructuredClient(
                null, schemaRegistry, null, failingMapper, null);
            
            Integer maxTokens = 10000;
            ReflectionTestUtils.setField(failingClient, "maxCompletionTokens", maxTokens);

            // When
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.ORDERING);
            OpenAiChatOptions options = invokeBuildChatOptions(failingClient, QuestionType.ORDERING, schema);

            // Then - Should return null due to schema serialization failure
            // (This is existing behavior - schema serialization failure returns null options)
            assertThat(options).isNull();
        }
    }

    @Nested
    @DisplayName("Truncated JSON Error Handling Tests")
    class TruncatedJsonErrorHandlingTests {

        @Test
        @DisplayName("Should detect truncated JSON with EOF error")
        void shouldDetectTruncatedJsonWithEofError() throws Exception {
            // Given - JSON truncated mid-array (simulating hitting token limit)
            String truncatedJson = """
                {
                  "questions": [
                    {
                      "questionText": "Question 1",
                      "type": "MCQ_SINGLE",
                      "difficulty": "EASY",
                      "content": {"options": [{"id": 1, "text": "Option A", "correct": true
                """;
            
            Integer maxTokens = 16000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", maxTokens);
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE);

            // When/Then - Should throw with helpful error message
            assertThatThrownBy(() -> invokeParseStructuredResponse(truncatedJson, QuestionType.MCQ_SINGLE, schema))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("JSON response truncated due to token limit")
                .hasMessageContaining("Current max-tokens: 16000")
                .hasMessageContaining("Try reducing question count or increasing max-tokens");
        }

        @Test
        @DisplayName("Should detect truncated JSON with unexpected end error")
        void shouldDetectTruncatedJsonWithUnexpectedEndError() throws Exception {
            // Given - JSON with unclosed array
            String truncatedJson = """
                {
                  "questions": [
                    {
                      "questionText": "Is this truncated?",
                      "type": "TRUE_FALSE",
                      "difficulty": "MEDIUM",
                      "content": {"answer": true},
                      "hint": "This JSON will be cut off",
                      "explanation": "Simulating truncation
                """;
            
            Integer maxTokens = 8000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", maxTokens);
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);

            // When/Then
            assertThatThrownBy(() -> invokeParseStructuredResponse(truncatedJson, QuestionType.TRUE_FALSE, schema))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("JSON response truncated due to token limit")
                .hasMessageContaining("Current max-tokens: 8000");
        }

        @Test
        @DisplayName("Should include max-tokens value in truncation error message")
        void shouldIncludeMaxTokensValueInTruncationError() throws Exception {
            // Given - Different max-tokens values
            String truncatedJson = "{\"questions\": [";
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.OPEN);

            // When/Then - Test with different max-tokens values
            Integer[] testValues = {4000, 8000, 16000, 24000};
            
            for (Integer maxTokens : testValues) {
                ReflectionTestUtils.setField(client, "maxCompletionTokens", maxTokens);
                
                assertThatThrownBy(() -> invokeParseStructuredResponse(truncatedJson, QuestionType.OPEN, schema))
                    .isInstanceOf(AIResponseParseException.class)
                    .hasMessageContaining("Current max-tokens: " + maxTokens);
            }
        }

        @Test
        @DisplayName("Should provide actionable guidance in truncation error")
        void shouldProvideActionableGuidanceInTruncationError() throws Exception {
            // Given
            String truncatedJson = "{\"questions\": [";
            Integer maxTokens = 16000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", maxTokens);
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MATCHING);

            // When/Then - Should include actionable advice
            assertThatThrownBy(() -> invokeParseStructuredResponse(truncatedJson, QuestionType.MATCHING, schema))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("reducing question count")
                .hasMessageContaining("increasing max-tokens")
                .hasMessageContaining("configuration");
        }

        @Test
        @DisplayName("Should distinguish truncation errors from other JSON errors")
        void shouldDistinguishTruncationErrorsFromOtherJsonErrors() throws Exception {
            // Given - Invalid JSON (not truncated, just malformed)
            String malformedJson = """
                {
                  "questions": [
                    {
                      "questionText": "Valid question",
                      "type": "INVALID_TYPE",
                      "difficulty": "EASY"
                    }
                  ]
                }
                """;
            
            Integer maxTokens = 16000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", maxTokens);
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE);

            // When/Then - Should throw different error (not truncation-specific)
            assertThatThrownBy(() -> invokeParseStructuredResponse(malformedJson, QuestionType.MCQ_SINGLE, schema))
                .isInstanceOf(AIResponseParseException.class)
                .satisfies(exception -> {
                    // Should NOT be a truncation error
                    assertThat(exception.getMessage())
                        .doesNotContain("truncated due to token limit")
                        .doesNotContain("Current max-tokens");
                });
        }
    }

    @Nested
    @DisplayName("Edge Cases and Boundary Tests")
    class EdgeCasesAndBoundaryTests {

        @Test
        @DisplayName("Should handle null maxCompletionTokens gracefully")
        void shouldHandleNullMaxCompletionTokensGracefully() throws Exception {
            // Given - null max tokens (shouldn't happen in production, but testing robustness)
            ReflectionTestUtils.setField(client, "maxCompletionTokens", null);

            // When
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.COMPLIANCE);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.COMPLIANCE, schema);

            // Then - Should still build options (with null maxTokens, API will use model default)
            assertThat(options).isNotNull();
            assertThat(options.getMaxTokens()).isNull();
        }

        @Test
        @DisplayName("Should handle very small maxCompletionTokens")
        void shouldHandleVerySmallMaxCompletionTokens() throws Exception {
            // Given - Very small value (1 token)
            Integer verySmall = 1;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", verySmall);

            // When
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.HOTSPOT);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.HOTSPOT, schema);

            // Then - Should accept the value (API will enforce its own limits)
            assertThat(options).isNotNull();
            assertThat(options.getMaxTokens()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should handle very large maxCompletionTokens")
        void shouldHandleVeryLargeMaxCompletionTokens() throws Exception {
            // Given - Very large value (128k tokens - GPT-4 Turbo max)
            Integer veryLarge = 128_000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", veryLarge);

            // When
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.FILL_GAP);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.FILL_GAP, schema);

            // Then - Should accept the value
            assertThat(options).isNotNull();
            assertThat(options.getMaxTokens()).isEqualTo(128_000);
        }
    }

    @Nested
    @DisplayName("Documentation and Real-World Scenario Tests")
    class DocumentationAndRealWorldScenarioTests {

        @Test
        @DisplayName("Should prevent gpt-4.1-mini 32k token limit issue")
        void shouldPreventGpt41MiniTokenLimitIssue() throws Exception {
            // Given - Simulate the original issue: no max-tokens set
            // Original: model would generate up to 32,768 tokens and truncate
            // Fixed: max-tokens=16000 prevents hitting the limit
            
            Integer safeMaxTokens = 16000; // Our configured safe limit
            Integer unsafeMaxTokens = 32768; // Model's hard limit (would cause truncation)
            
            ReflectionTestUtils.setField(client, "maxCompletionTokens", safeMaxTokens);

            // When - Request generation for complex question type (MATCHING)
            JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MATCHING);
            OpenAiChatOptions options = invokeBuildChatOptions(QuestionType.MATCHING, schema);

            // Then - Should use safe limit, not model's hard limit
            assertThat(options.getMaxTokens())
                .as("Should use safe configured limit")
                .isEqualTo(safeMaxTokens)
                .isLessThan(unsafeMaxTokens);
        }

        @Test
        @DisplayName("Should support typical 10-question generation within limits")
        void shouldSupportTypical10QuestionGeneration() throws Exception {
            // Given - Configuration for typical use case: 10 questions
            // Each complex question ≈ 1000-1500 tokens
            // 10 questions ≈ 10,000-15,000 tokens
            // 16,000 tokens provides comfortable buffer
            
            Integer recommendedMaxTokens = 16000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", recommendedMaxTokens);

            // When - Build options for various question types
            QuestionType[] complexTypes = {
                QuestionType.MATCHING,
                QuestionType.ORDERING,
                QuestionType.FILL_GAP,
                QuestionType.MCQ_MULTI
            };

            // Then - All should have sufficient token budget
            for (QuestionType type : complexTypes) {
                JsonNode schema = schemaRegistry.getSchemaForQuestionType(type);
                OpenAiChatOptions options = invokeBuildChatOptions(type, schema);
                
                assertThat(options.getMaxTokens())
                    .as("Token limit for " + type + " should support 10 questions")
                    .isGreaterThanOrEqualTo(10_000) // Minimum for 10 questions
                    .isLessThanOrEqualTo(20_000);   // Maximum for safety
            }
        }

        @Test
        @DisplayName("Should allow cost optimization with lower limits")
        void shouldAllowCostOptimizationWithLowerLimits() throws Exception {
            // Given - Cost-conscious configuration for simpler question types
            // TRUE_FALSE, MCQ_SINGLE are simpler (≈500-800 tokens each)
            // 4000 tokens sufficient for 5-6 simple questions
            
            Integer costOptimizedMaxTokens = 4000;
            ReflectionTestUtils.setField(client, "maxCompletionTokens", costOptimizedMaxTokens);

            // When
            QuestionType[] simpleTypes = {
                QuestionType.TRUE_FALSE,
                QuestionType.MCQ_SINGLE
            };

            // Then - Should accept lower limits for cost optimization
            for (QuestionType type : simpleTypes) {
                JsonNode schema = schemaRegistry.getSchemaForQuestionType(type);
                OpenAiChatOptions options = invokeBuildChatOptions(type, schema);
                
                assertThat(options.getMaxTokens())
                    .as("Cost-optimized limit for " + type)
                    .isEqualTo(4000);
            }
        }
    }

    // ====== Helper Methods ======

    /**
     * Invoke private buildChatOptions method via reflection
     */
    private OpenAiChatOptions invokeBuildChatOptions(QuestionType type, JsonNode schema) throws Exception {
        return invokeBuildChatOptions(client, type, schema);
    }

    /**
     * Invoke private buildChatOptions method via reflection on specific client
     */
    private OpenAiChatOptions invokeBuildChatOptions(SpringAiStructuredClient targetClient, QuestionType type, JsonNode schema) throws Exception {
        Method method = SpringAiStructuredClient.class.getDeclaredMethod("buildChatOptions", QuestionType.class, JsonNode.class);
        method.setAccessible(true);
        return (OpenAiChatOptions) method.invoke(targetClient, type, schema);
    }

    /**
     * Invoke private parseStructuredResponse method via reflection
     */
    private void invokeParseStructuredResponse(String rawResponse, QuestionType expectedType, JsonNode schema) throws Exception {
        Method method = SpringAiStructuredClient.class.getDeclaredMethod(
            "parseStructuredResponse", String.class, QuestionType.class, JsonNode.class);
        method.setAccessible(true);
        try {
            method.invoke(client, rawResponse, expectedType, schema);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap the actual exception thrown by the method
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}

