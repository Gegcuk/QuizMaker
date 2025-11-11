package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SpringAiStructuredClient parsing logic.
 * Tests JSON parsing, markdown cleaning, and error handling.
 */
@DisplayName("SpringAiStructuredClient - Parsing Tests")
class SpringAiStructuredClientParsingTest {
    
    private QuestionSchemaRegistry schemaRegistry;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        
        // For parsing tests, we don't need full client setup
        // We'll test helper methods via reflection
    }
    
    // ====== Markdown Code Block Cleaning Tests ======
    
    @Test
    @DisplayName("Should remove ```json markdown blocks from response")
    void shouldRemoveJsonMarkdownBlocks() throws Exception {
        // Given
        String jsonWithMarkdown = """
            ```json
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
        
        // When
        String cleaned = invokeCleanJsonResponse(jsonWithMarkdown);
        
        // Then
        assertThat(cleaned).doesNotContain("```json");
        assertThat(cleaned).doesNotContain("```");
        assertThat(cleaned).startsWith("{");
        assertThat(cleaned).endsWith("}");
    }
    
    @Test
    @DisplayName("Should remove generic ``` markdown blocks from response")
    void shouldRemoveGenericMarkdownBlocks() throws Exception {
        // Given
        String jsonWithGenericBlocks = """
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
        
        // When
        String cleaned = invokeCleanJsonResponse(jsonWithGenericBlocks);
        
        // Then
        assertThat(cleaned).doesNotContain("```");
        assertThat(cleaned).startsWith("{");
        assertThat(cleaned).endsWith("}");
    }
    
    @Test
    @DisplayName("Should trim whitespace from response")
    void shouldTrimWhitespaceFromResponse() throws Exception {
        // Given
        String jsonWithWhitespace = "\n\n   {\"test\": \"value\"}   \n\n";
        
        // When
        String cleaned = invokeCleanJsonResponse(jsonWithWhitespace);
        
        // Then
        assertThat(cleaned).isEqualTo("{\"test\": \"value\"}");
    }
    
    @Test
    @DisplayName("Should handle response without markdown blocks")
    void shouldHandleResponseWithoutMarkdown() throws Exception {
        // Given
        String plainJson = "{\"test\": \"value\"}";
        
        // When
        String cleaned = invokeCleanJsonResponse(plainJson);
        
        // Then
        assertThat(cleaned).isEqualTo(plainJson);
    }
    
    // ====== Parse Question Tests (Per Type) ======
    
    @Test
    @DisplayName("Should parse TRUE_FALSE question successfully")
    void shouldParseTrueFalseQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "The sky is blue",
              "type": "TRUE_FALSE",
              "difficulty": "EASY",
              "content": {
                "answer": true
              },
              "hint": "Think about daylight",
              "explanation": "Due to Rayleigh scattering",
              "confidence": 0.95
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question).isNotNull();
        assertThat(question.getQuestionText()).isEqualTo("The sky is blue");
        assertThat(question.getType()).isEqualTo(QuestionType.TRUE_FALSE);
        assertThat(question.getDifficulty()).isEqualTo(Difficulty.EASY);
        assertThat(question.getHint()).isEqualTo("Think about daylight");
        assertThat(question.getExplanation()).isEqualTo("Due to Rayleigh scattering");
        
        // Verify content is valid JSON string
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("answer")).isTrue();
        assertThat(content.get("answer").asBoolean()).isTrue();
    }
    
    @Test
    @DisplayName("Should parse MCQ_SINGLE question successfully")
    void shouldParseMcqSingleQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "What is 2+2?",
              "type": "MCQ_SINGLE",
              "difficulty": "EASY",
              "content": {
                "options": [
                  {"id": "a", "text": "3", "correct": false},
                  {"id": "b", "text": "4", "correct": true},
                  {"id": "c", "text": "5", "correct": false},
                  {"id": "d", "text": "6", "correct": false}
                ]
              },
              "hint": "Basic arithmetic",
              "explanation": "2+2 equals 4",
              "confidence": 0.99
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question).isNotNull();
        assertThat(question.getType()).isEqualTo(QuestionType.MCQ_SINGLE);
        
        // Verify content contains options array
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("options")).isTrue();
        assertThat(content.get("options").isArray()).isTrue();
        assertThat(content.get("options")).hasSize(4);
    }
    
    @Test
    @DisplayName("Should parse FILL_GAP question successfully")
    void shouldParseFillGapQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "Complete the sentence",
              "type": "FILL_GAP",
              "difficulty": "MEDIUM",
              "content": {
                "text": "The capital of {1} is Paris",
                "gaps": [
                  {"id": 1, "answer": "France"}
                ]
              },
              "hint": "Think about European countries",
              "explanation": "France is a country in Europe with Paris as its capital",
              "confidence": 0.98
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question.getType()).isEqualTo(QuestionType.FILL_GAP);
        
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("text")).isTrue();
        assertThat(content.has("gaps")).isTrue();
        assertThat(content.get("gaps").isArray()).isTrue();
    }
    
    @Test
    @DisplayName("Should parse ORDERING question successfully")
    void shouldParseOrderingQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "Order these events",
              "type": "ORDERING",
              "difficulty": "MEDIUM",
              "content": {
                "items": [
                  {"id": 1, "text": "First event"},
                  {"id": 2, "text": "Second event"},
                  {"id": 3, "text": "Third event"}
                ]
              },
              "hint": "Consider chronological order",
              "explanation": "Events are ordered from earliest to latest",
              "confidence": 0.92
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question.getType()).isEqualTo(QuestionType.ORDERING);
        
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("items")).isTrue();
        assertThat(content.get("items").isArray()).isTrue();
        assertThat(content.get("items")).hasSize(3);
    }
    
    @Test
    @DisplayName("Should parse HOTSPOT question successfully")
    void shouldParseHotspotQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "Click the heart",
              "type": "HOTSPOT",
              "difficulty": "EASY",
              "content": {
                "imageUrl": "https://example.com/anatomy.jpg",
                "regions": [
                  {"id": 1, "x": 100, "y": 150, "width": 50, "height": 60, "correct": true},
                  {"id": 2, "x": 200, "y": 150, "width": 50, "height": 60, "correct": false}
                ]
              },
              "hint": "Look for the organ on the left side",
              "explanation": "The heart is located on the left side of the chest",
              "confidence": 0.97
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question.getType()).isEqualTo(QuestionType.HOTSPOT);
        
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("imageUrl")).isTrue();
        assertThat(content.has("regions")).isTrue();
        assertThat(content.get("regions").isArray()).isTrue();
    }
    
    @Test
    @DisplayName("Should parse COMPLIANCE question successfully")
    void shouldParseComplianceQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "Which actions are compliant?",
              "type": "COMPLIANCE",
              "difficulty": "HARD",
              "content": {
                "statements": [
                  {"id": 1, "text": "Report the incident", "compliant": true},
                  {"id": 2, "text": "Ignore the issue", "compliant": false},
                  {"id": 3, "text": "Document findings", "compliant": true}
                ]
              },
              "hint": "Consider regulatory requirements",
              "explanation": "Compliant actions follow proper protocols and regulations",
              "confidence": 0.94
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question.getType()).isEqualTo(QuestionType.COMPLIANCE);
        
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("statements")).isTrue();
        assertThat(content.get("statements").isArray()).isTrue();
        assertThat(content.get("statements")).hasSize(3);
    }
    
    // ====== Parse Question Error Tests ======
    
    @Test
    @DisplayName("Should fail when questionText is missing")
    void shouldFailWhenQuestionTextMissing() throws Exception {
        // Given
        String invalidJson = """
            {
              "type": "TRUE_FALSE",
              "difficulty": "EASY",
              "content": {"answer": true}
            }
            """;
        JsonNode questionNode = objectMapper.readTree(invalidJson);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseQuestion(questionNode))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("missing required fields");
    }
    
    @Test
    @DisplayName("Should fail when type is missing")
    void shouldFailWhenTypeMissing() throws Exception {
        // Given
        String invalidJson = """
            {
              "questionText": "Test",
              "difficulty": "EASY",
              "content": {"answer": true}
            }
            """;
        JsonNode questionNode = objectMapper.readTree(invalidJson);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseQuestion(questionNode))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("missing required fields");
    }
    
    @Test
    @DisplayName("Should fail when difficulty is missing")
    void shouldFailWhenDifficultyMissing() throws Exception {
        // Given
        String invalidJson = """
            {
              "questionText": "Test",
              "type": "TRUE_FALSE",
              "content": {"answer": true}
            }
            """;
        JsonNode questionNode = objectMapper.readTree(invalidJson);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseQuestion(questionNode))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("missing required fields");
    }
    
    @Test
    @DisplayName("Should fail when content is missing")
    void shouldFailWhenContentMissing() throws Exception {
        // Given
        String invalidJson = """
            {
              "questionText": "Test",
              "type": "TRUE_FALSE",
              "difficulty": "EASY"
            }
            """;
        JsonNode questionNode = objectMapper.readTree(invalidJson);
        
        // When/Then
        assertThatThrownBy(() -> invokeParseQuestion(questionNode))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("missing required fields");
    }
    
    @Test
    @DisplayName("Should fail when required fields are missing (hint, explanation, confidence)")
    void shouldHandleMissingOptionalFields() throws Exception {
        // Given - missing required fields (hint, explanation, confidence)
        String questionJson = """
            {
              "questionText": "Test",
              "type": "TRUE_FALSE",
              "difficulty": "EASY",
              "content": {"answer": true}
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When/Then - should fail validation (fields are now required in strict mode)
        assertThatThrownBy(() -> invokeParseQuestion(questionNode))
                .isInstanceOf(AIResponseParseException.class)
                .hasMessageContaining("missing required fields");
    }
    
    @Test
    @DisplayName("Should fail when required fields have null values")
    void shouldHandleNullOptionalFields() throws Exception {
        // Given - required fields with null values
        String questionJson = """
            {
              "questionText": "Test",
              "type": "TRUE_FALSE",
              "difficulty": "EASY",
              "content": {"answer": true},
              "hint": null,
              "explanation": null,
              "confidence": null
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When/Then - should fail (null values not allowed for required fields)
        assertThatThrownBy(() -> invokeParseQuestion(questionNode))
                .isInstanceOf(Exception.class);
    }
    
    @Test
    @DisplayName("Should parse MATCHING question successfully")
    void shouldParseMatchingQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "Match the terms with definitions",
              "type": "MATCHING",
              "difficulty": "MEDIUM",
              "content": {
                "left": [
                  {"id": 1, "text": "Term 1", "matchId": 10},
                  {"id": 2, "text": "Term 2", "matchId": 11},
                  {"id": 3, "text": "Term 3", "matchId": 12},
                  {"id": 4, "text": "Term 4", "matchId": 13}
                ],
                "right": [
                  {"id": 10, "text": "Definition 1"},
                  {"id": 11, "text": "Definition 2"},
                  {"id": 12, "text": "Definition 3"},
                  {"id": 13, "text": "Definition 4"}
                ]
              },
              "hint": "Read all definitions carefully before matching",
              "explanation": "Each term matches with its corresponding definition",
              "confidence": 0.91
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question.getType()).isEqualTo(QuestionType.MATCHING);
        
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("left")).isTrue();
        assertThat(content.has("right")).isTrue();
        assertThat(content.get("left").isArray()).isTrue();
        assertThat(content.get("right").isArray()).isTrue();
        assertThat(content.get("left")).hasSize(4);
        assertThat(content.get("right")).hasSize(4);
    }
    
    @Test
    @DisplayName("Should parse OPEN question successfully")
    void shouldParseOpenQuestion() throws Exception {
        // Given
        String questionJson = """
            {
              "questionText": "Explain photosynthesis",
              "type": "OPEN",
              "difficulty": "MEDIUM",
              "content": {
                "answer": "Photosynthesis is the process by which plants convert light energy into chemical energy"
              },
              "hint": "Think about how plants use sunlight",
              "explanation": "Plants use chlorophyll to capture light energy and convert it to chemical energy",
              "confidence": 0.96
            }
            """;
        JsonNode questionNode = objectMapper.readTree(questionJson);
        
        // When
        StructuredQuestion question = invokeParseQuestion(questionNode);
        
        // Then
        assertThat(question.getType()).isEqualTo(QuestionType.OPEN);
        
        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.has("answer")).isTrue();
        assertThat(content.get("answer").asText()).contains("Photosynthesis");
    }
    
    // ====== Helper Methods ======
    
    /**
     * Invoke private cleanJsonResponse method via reflection
     */
    private String invokeCleanJsonResponse(String response) throws Exception {
        // Create a minimal instance for testing utility methods
        SpringAiStructuredClient client = new SpringAiStructuredClient(
                null, schemaRegistry, null, objectMapper, null);
        
        Method method = SpringAiStructuredClient.class.getDeclaredMethod("cleanJsonResponse", String.class);
        method.setAccessible(true);
        return (String) method.invoke(client, response);
    }
    
    /**
     * Invoke private parseQuestion method via reflection.
     * Unwraps InvocationTargetException to get the actual exception.
     */
    private StructuredQuestion invokeParseQuestion(JsonNode questionNode) throws Exception {
        SpringAiStructuredClient client = new SpringAiStructuredClient(
                null, schemaRegistry, null, objectMapper, null);
        
        Method method = SpringAiStructuredClient.class.getDeclaredMethod("parseQuestion", JsonNode.class);
        method.setAccessible(true);
        try {
            return (StructuredQuestion) method.invoke(client, questionNode);
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
