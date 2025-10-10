package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SpringAiStructuredClient's parseStructuredResponse method.
 * Tests full response parsing including multiple questions and error scenarios.
 */
@DisplayName("SpringAiStructuredClient - Full Response Parsing")
class SpringAiStructuredClientResponseParsingTest {
    
    private QuestionSchemaRegistry schemaRegistry;
    private ObjectMapper objectMapper;
    private SpringAiStructuredClient client;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        client = new SpringAiStructuredClient(null, schemaRegistry, null, objectMapper, null);
    }
    
    // ====== Successful Parsing Tests ======
    
    @Test
    @DisplayName("Should parse response with single valid question")
    void shouldParseSingleValidQuestion() throws Exception {
        // Given
        String validResponse = """
            {
              "questions": [
                {
                  "questionText": "Is the sky blue?",
                  "type": "TRUE_FALSE",
                  "difficulty": "EASY",
                  "content": {"answer": true}
                }
              ]
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When
        StructuredQuestionResponse response = invokeParseStructuredResponse(
                validResponse, QuestionType.TRUE_FALSE, schema);
        
        // Then
        assertThat(response).isNotNull();
        assertThat(response.getQuestions()).hasSize(1);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.isSchemaValid()).isTrue();
        assertThat(response.getQuestions().get(0).getQuestionText()).isEqualTo("Is the sky blue?");
    }
    
    @Test
    @DisplayName("Should parse response with multiple valid questions")
    void shouldParseMultipleValidQuestions() throws Exception {
        // Given
        String validResponse = """
            {
              "questions": [
                {
                  "questionText": "Question 1",
                  "type": "TRUE_FALSE",
                  "difficulty": "EASY",
                  "content": {"answer": true}
                },
                {
                  "questionText": "Question 2",
                  "type": "TRUE_FALSE",
                  "difficulty": "MEDIUM",
                  "content": {"answer": false}
                },
                {
                  "questionText": "Question 3",
                  "type": "TRUE_FALSE",
                  "difficulty": "HARD",
                  "content": {"answer": true}
                }
              ]
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When
        StructuredQuestionResponse response = invokeParseStructuredResponse(
                validResponse, QuestionType.TRUE_FALSE, schema);
        
        // Then
        assertThat(response.getQuestions()).hasSize(3);
        assertThat(response.getWarnings()).isEmpty();
        assertThat(response.isSchemaValid()).isTrue();
    }
    
    // ====== Type Mismatch Warnings Tests ======
    
    @Test
    @DisplayName("Should collect warning for question type mismatch")
    void shouldCollectWarningForTypeMismatch() throws Exception {
        // Given - expecting TRUE_FALSE but got OPEN
        String responseWithMismatch = """
            {
              "questions": [
                {
                  "questionText": "Explain something",
                  "type": "OPEN",
                  "difficulty": "MEDIUM",
                  "content": {"answer": "A detailed explanation"}
                }
              ]
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When
        StructuredQuestionResponse response = invokeParseStructuredResponse(
                responseWithMismatch, QuestionType.TRUE_FALSE, schema);
        
        // Then
        assertThat(response.getQuestions()).hasSize(1);
        assertThat(response.getWarnings()).isNotEmpty();
        assertThat(response.getWarnings().get(0))
                .contains("type mismatch")
                .contains("TRUE_FALSE")
                .contains("OPEN");
    }
    
    // ====== Mixed Valid/Invalid Questions Tests ======
    
    @Test
    @DisplayName("Should parse valid questions and collect warnings for invalid ones")
    void shouldParseValidQuestionsAndCollectWarnings() throws Exception {
        // Given - mixed good and bad questions
        String mixedResponse = """
            {
              "questions": [
                {
                  "questionText": "Valid question 1",
                  "type": "TRUE_FALSE",
                  "difficulty": "EASY",
                  "content": {"answer": true}
                },
                {
                  "questionText": "Invalid - missing content",
                  "type": "TRUE_FALSE",
                  "difficulty": "EASY"
                },
                {
                  "questionText": "Valid question 2",
                  "type": "TRUE_FALSE",
                  "difficulty": "MEDIUM",
                  "content": {"answer": false}
                }
              ]
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When
        StructuredQuestionResponse response = invokeParseStructuredResponse(
                mixedResponse, QuestionType.TRUE_FALSE, schema);
        
        // Then - should have 2 valid questions and 1 warning
        assertThat(response.getQuestions()).hasSize(2);
        assertThat(response.getWarnings()).hasSize(1);
        assertThat(response.getWarnings().get(0)).contains("Failed to parse question");
    }
    
    // ====== Error Tests (Must Fail) ======
    
    @Test
    @DisplayName("Should fail when 'questions' field is missing")
    void shouldFailWhenQuestionsFieldMissing() throws Exception {
        // Given
        String invalidResponse = """
            {
              "data": []
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseStructuredResponse(invalidResponse, QuestionType.TRUE_FALSE, schema))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("missing 'questions' field");
    }
    
    @Test
    @DisplayName("Should fail when 'questions' is not an array")
    void shouldFailWhenQuestionsNotArray() throws Exception {
        // Given
        String invalidResponse = """
            {
              "questions": "not an array"
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseStructuredResponse(invalidResponse, QuestionType.TRUE_FALSE, schema))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("'questions' field must be an array");
    }
    
    @Test
    @DisplayName("Should fail when response is not valid JSON")
    void shouldFailWhenResponseNotValidJson() throws Exception {
        // Given
        String invalidJson = "This is not JSON at all!";
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseStructuredResponse(invalidJson, QuestionType.TRUE_FALSE, schema))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("Invalid JSON");
    }
    
    @Test
    @DisplayName("Should fail when all questions fail to parse")
    void shouldFailWhenAllQuestionsFail() throws Exception {
        // Given - all questions invalid
        String allInvalidResponse = """
            {
              "questions": [
                {
                  "type": "TRUE_FALSE",
                  "difficulty": "EASY"
                },
                {
                  "questionText": "Missing type",
                  "difficulty": "EASY",
                  "content": {"answer": true}
                }
              ]
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseStructuredResponse(allInvalidResponse, QuestionType.TRUE_FALSE, schema))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("No valid questions parsed");
    }
    
    // ====== Markdown Cleaning Integration Tests ======
    
    @Test
    @DisplayName("Should clean markdown blocks before parsing")
    void shouldCleanMarkdownBeforeParsing() throws Exception {
        // Given - response wrapped in markdown
        String responseWithMarkdown = """
            ```json
            {
              "questions": [
                {
                  "questionText": "Test question",
                  "type": "TRUE_FALSE",
                  "difficulty": "EASY",
                  "content": {"answer": true}
                }
              ]
            }
            ```
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When
        StructuredQuestionResponse response = invokeParseStructuredResponse(
                responseWithMarkdown, QuestionType.TRUE_FALSE, schema);
        
        // Then - should parse successfully despite markdown
        assertThat(response.getQuestions()).hasSize(1);
        assertThat(response.getWarnings()).isEmpty();
    }
    
    @Test
    @DisplayName("Should handle response with extra whitespace and markdown")
    void shouldHandleWhitespaceAndMarkdown() throws Exception {
        // Given
        String messyResponse = """
            
            
                ```
                {
                  "questions": [
                    {
                      "questionText": "Test",
                      "type": "TRUE_FALSE",
                      "difficulty": "EASY",
                      "content": {"answer": true}
                    }
                  ]
                }
                ```
            
            
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        
        // When
        StructuredQuestionResponse response = invokeParseStructuredResponse(
                messyResponse, QuestionType.TRUE_FALSE, schema);
        
        // Then
        assertThat(response.getQuestions()).hasSize(1);
    }
    
    // ====== Content Serialization Tests ======
    
    @Test
    @DisplayName("Should serialize content as JSON string")
    void shouldSerializeContentAsJsonString() throws Exception {
        // Given
        String response = """
            {
              "questions": [
                {
                  "questionText": "Test",
                  "type": "MCQ_SINGLE",
                  "difficulty": "EASY",
                  "content": {
                    "options": [
                      {"text": "Option 1", "correct": false},
                      {"text": "Option 2", "correct": true}
                    ]
                  }
                }
              ]
            }
            """;
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE);
        
        // When
        StructuredQuestionResponse parsedResponse = invokeParseStructuredResponse(
                response, QuestionType.MCQ_SINGLE, schema);
        
        // Then - content should be valid JSON string
        String contentJson = parsedResponse.getQuestions().get(0).getContent();
        assertThat(contentJson).isNotNull();
        
        // Verify it can be parsed back
        JsonNode content = objectMapper.readTree(contentJson);
        assertThat(content.has("options")).isTrue();
        assertThat(content.get("options").isArray()).isTrue();
    }
    
    // ====== Helper Method ======
    
    /**
     * Invoke private parseStructuredResponse method via reflection.
     * Unwraps InvocationTargetException to get the actual exception.
     */
    private StructuredQuestionResponse invokeParseStructuredResponse(
            String rawResponse, QuestionType expectedType, JsonNode schema) throws Exception {
        Method method = SpringAiStructuredClient.class.getDeclaredMethod(
                "parseStructuredResponse", String.class, QuestionType.class, JsonNode.class);
        method.setAccessible(true);
        try {
            return (StructuredQuestionResponse) method.invoke(client, rawResponse, expectedType, schema);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap the actual exception
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}

