package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SpringAiStructuredClient validation logic.
 * Focuses on content structure validation and error handling.
 */
@DisplayName("SpringAiStructuredClient - Validation Tests")
class SpringAiStructuredClientValidationTest {
    
    private QuestionSchemaRegistry schemaRegistry;
    private ObjectMapper objectMapper;
    private SpringAiStructuredClient structuredClient;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        
        // Create minimal client for testing validation methods via reflection
        structuredClient = new SpringAiStructuredClient(
                null,  // ChatClient not needed for validation tests
                schemaRegistry,
                null,  // PromptTemplateService not needed
                objectMapper,
                null   // AiRateLimitConfig not needed
        );
    }
    
    // ====== Request Validation Tests ======
    
    @Test
    @DisplayName("Should reject request with empty chunk content")
    void shouldRejectEmptyChunkContent() {
        // Given
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(1)
                .difficulty(Difficulty.EASY)
                .build();
        
        // When/Then
        assertThatThrownBy(() -> structuredClient.generateQuestions(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content cannot be empty");
    }
    
    @Test
    @DisplayName("Should reject request with null chunk content")
    void shouldRejectNullChunkContent() {
        // Given
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent(null)
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(1)
                .difficulty(Difficulty.EASY)
                .build();
        
        // When/Then
        assertThatThrownBy(() -> structuredClient.generateQuestions(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content cannot be empty");
    }
    
    @Test
    @DisplayName("Should reject request with null question type")
    void shouldRejectNullQuestionType() {
        // Given
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Valid content")
                .questionType(null)
                .questionCount(1)
                .difficulty(Difficulty.EASY)
                .build();
        
        // When/Then
        assertThatThrownBy(() -> structuredClient.generateQuestions(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question type cannot be null");
    }
    
    @ParameterizedTest
    @ValueSource(ints = {0, -1, -10})
    @DisplayName("Should reject request with non-positive question count")
    void shouldRejectNonPositiveQuestionCount(int invalidCount) {
        // Given
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Valid content")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(invalidCount)
                .difficulty(Difficulty.EASY)
                .build();
        
        // When/Then
        assertThatThrownBy(() -> structuredClient.generateQuestions(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count must be positive");
    }
    
    @Test
    @DisplayName("Should reject request with null difficulty")
    void shouldRejectNullDifficulty() {
        // Given
        StructuredQuestionRequest request = StructuredQuestionRequest.builder()
                .chunkContent("Valid content")
                .questionType(QuestionType.MCQ_SINGLE)
                .questionCount(1)
                .difficulty(null)
                .build();
        
        // When/Then
        assertThatThrownBy(() -> structuredClient.generateQuestions(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Difficulty cannot be null");
    }
    
    // ====== Content Structure Validation Tests (Using Reflection) ======
    
    @Test
    @DisplayName("TRUE_FALSE content validation should require boolean 'answer'")
    void trueFalseValidationShouldRequireBooleanAnswer() throws Exception {
        // Given
        String invalidContent = """
            {
              "answer": "yes"
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.TRUE_FALSE))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("TRUE_FALSE")
                .hasMessageContaining("boolean 'answer'");
    }
    
    @Test
    @DisplayName("TRUE_FALSE content validation should pass with boolean answer")
    void trueFalseValidationShouldPassWithBooleanAnswer() throws Exception {
        // Given
        String validContent = """
            {
              "answer": true
            }
            """;
        JsonNode contentNode = objectMapper.readTree(validContent);
        
        // When/Then - should not throw
        invokeValidateContentStructure(contentNode, QuestionType.TRUE_FALSE);
    }
    
    @Test
    @DisplayName("MCQ content validation should require 'options' array")
    void mcqValidationShouldRequireOptionsArray() throws Exception {
        // Given
        String invalidContent = """
            {
              "choices": []
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.MCQ_SINGLE))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("MCQ")
                .hasMessageContaining("'options' array");
    }
    
    @Test
    @DisplayName("MCQ content validation should pass with valid options array")
    void mcqValidationShouldPassWithOptionsArray() throws Exception {
        // Given
        String validContent = """
            {
              "options": [
                {"text": "Option 1", "correct": false},
                {"text": "Option 2", "correct": true}
              ]
            }
            """;
        JsonNode contentNode = objectMapper.readTree(validContent);
        
        // When/Then - should not throw
        invokeValidateContentStructure(contentNode, QuestionType.MCQ_SINGLE);
    }
    
    @Test
    @DisplayName("OPEN content validation should require non-empty 'answer'")
    void openValidationShouldRequireNonEmptyAnswer() throws Exception {
        // Given - empty answer
        String invalidContent = """
            {
              "answer": "   "
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.OPEN))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("OPEN")
                .hasMessageContaining("non-empty 'answer'");
    }
    
    @Test
    @DisplayName("FILL_GAP content validation should require 'text' and 'gaps'")
    void fillGapValidationShouldRequireTextAndGaps() throws Exception {
        // Given - missing gaps
        String invalidContent = """
            {
              "text": "Some text with {1}"
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.FILL_GAP))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("FILL_GAP must have at least one gap defined");
    }
    
    @Test
    @DisplayName("FILL_GAP content validation should require gaps to be array")
    void fillGapValidationShouldRequireGapsArray() throws Exception {
        // Given - gaps is not an array
        String invalidContent = """
            {
              "text": "Some text",
              "gaps": "not an array"
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.FILL_GAP))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("FILL_GAP must have at least one gap defined");
    }
    
    @Test
    @DisplayName("FILL_GAP content validation should pass with valid structure")
    void fillGapValidationShouldPassWithValidStructure() throws Exception {
        // Given
        String validContent = """
            {
              "text": "The capital is {1}",
              "gaps": [
                {"id": 1, "answer": "Paris"}
              ]
            }
            """;
        JsonNode contentNode = objectMapper.readTree(validContent);
        
        // When/Then - should not throw
        invokeValidateContentStructure(contentNode, QuestionType.FILL_GAP);
    }
    
    @Test
    @DisplayName("FILL_GAP validation should require {N} placeholders in text")
    void fillGapValidationShouldRequirePlaceholders() throws Exception {
        String content = """
            {
              "text": "No placeholders here",
              "gaps": [
                {"id": 1, "answer": "value"}
              ]
            }
            """;
        JsonNode node = objectMapper.readTree(content);
        
        assertThatThrownBy(() -> invokeValidateContentStructure(node, QuestionType.FILL_GAP))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("No gaps found in text");
    }
    
    @Test
    @DisplayName("FILL_GAP validation should require gaps to match placeholders")
    void fillGapValidationShouldRequireMatchingGaps() throws Exception {
        String content = """
            {
              "text": "The capital of {1} is {2}",
              "gaps": [
                {"id": 1, "answer": "France"}
              ]
            }
            """;
        JsonNode node = objectMapper.readTree(content);
        
        assertThatThrownBy(() -> invokeValidateContentStructure(node, QuestionType.FILL_GAP))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("id=2");
    }
    
    @Test
    @DisplayName("FILL_GAP validation should enforce sequential gap IDs")
    void fillGapValidationShouldEnforceSequentialIds() throws Exception {
        String content = """
            {
              "text": "The capital of {1} is {3}",
              "gaps": [
                {"id": 1, "answer": "France"},
                {"id": 3, "answer": "Europe"}
              ]
            }
            """;
        JsonNode node = objectMapper.readTree(content);
        
        assertThatThrownBy(() -> invokeValidateContentStructure(node, QuestionType.FILL_GAP))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("sequential integers");
    }
    
    @Test
    @DisplayName("ORDERING content validation should require 'items' array")
    void orderingValidationShouldRequireItemsArray() throws Exception {
        // Given
        String invalidContent = """
            {
              "steps": []
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.ORDERING))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("ORDERING")
                .hasMessageContaining("'items' array");
    }
    
    @Test
    @DisplayName("MATCHING content validation should require 'left' and 'right' arrays")
    void matchingValidationShouldRequireLeftAndRightArrays() throws Exception {
        // Given - missing right array
        String invalidContent = """
            {
              "left": []
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.MATCHING))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("MATCHING")
                .hasMessageContaining("'left' and 'right' arrays");
    }
    
    @Test
    @DisplayName("HOTSPOT content validation should require 'imageUrl' and 'regions'")
    void hotspotValidationShouldRequireImageUrlAndRegions() throws Exception {
        // Given - missing regions
        String invalidContent = """
            {
              "imageUrl": "https://example.com/image.jpg"
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.HOTSPOT))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("HOTSPOT")
                .hasMessageContaining("'imageUrl' and 'regions'");
    }
    
    @Test
    @DisplayName("HOTSPOT content validation should require regions to be array")
    void hotspotValidationShouldRequireRegionsArray() throws Exception {
        // Given
        String invalidContent = """
            {
              "imageUrl": "https://example.com/image.jpg",
              "regions": "not an array"
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.HOTSPOT))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("'regions' must be an array");
    }
    
    @Test
    @DisplayName("COMPLIANCE content validation should require 'statements' array")
    void complianceValidationShouldRequireStatementsArray() throws Exception {
        // Given
        String invalidContent = """
            {
              "rules": []
            }
            """;
        JsonNode contentNode = objectMapper.readTree(invalidContent);
        
        // When/Then
        assertThatThrownBy(() -> invokeValidateContentStructure(contentNode, QuestionType.COMPLIANCE))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("COMPLIANCE")
                .hasMessageContaining("'statements' array");
    }
    
    // ====== Helper Methods ======
    
    /**
     * Invoke the private validateContentStructure method via reflection for testing.
     * Unwraps InvocationTargetException to get the actual AIResponseParseException.
     */
    private void invokeValidateContentStructure(JsonNode contentNode, QuestionType type) throws Exception {
        Method method = SpringAiStructuredClient.class.getDeclaredMethod(
                "validateContentStructure", JsonNode.class, QuestionType.class);
        method.setAccessible(true);
        try {
            method.invoke(structuredClient, contentNode, type);
        } catch (InvocationTargetException e) {
            // Unwrap the actual exception thrown by the method
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}
