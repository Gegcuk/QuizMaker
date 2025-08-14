package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class OrderingQuestionParserTest {

    private OrderingQuestionParser parser;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        parser = new OrderingQuestionParser();
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldParseValidOrderingQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the following steps in the correct order",
                  "difficulty": "MEDIUM",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Write the code"},
                      {"id": 2, "text": "Compile the code"},
                      {"id": 3, "text": "Run the program"},
                      {"id": 4, "text": "Debug if needed"}
                    ]
                  },
                  "hint": "Think about the software development lifecycle",
                  "explanation": "The correct order follows the typical software development process"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseOrderingQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals("Arrange the following steps in the correct order", question.getQuestionText());
        assertEquals(QuestionType.ORDERING, question.getType());
        assertEquals(Difficulty.MEDIUM, question.getDifficulty());
        assertEquals("Think about the software development lifecycle", question.getHint());
        assertEquals("The correct order follows the typical software development process", question.getExplanation());
        
        // Verify content structure
        JsonNode content = objectMapper.readTree(question.getContent());
        assertTrue(content.has("items"));
        assertEquals(4, content.get("items").size());
        assertEquals(1, content.get("items").get(0).get("id").asInt());
        assertEquals("Write the code", content.get("items").get(0).get("text").asText());
        assertEquals(2, content.get("items").get(1).get("id").asInt());
        assertEquals("Compile the code", content.get("items").get(1).get("text").asText());
    }

    @Test
    void shouldParseMultipleOrderingQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Order the Java compilation steps",
                  "difficulty": "EASY",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Write Java source code"},
                      {"id": 2, "text": "Compile to bytecode"},
                      {"id": 3, "text": "Run on JVM"}
                    ]
                  }
                },
                {
                  "questionText": "Arrange the Spring Boot startup sequence",
                  "difficulty": "HARD",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Load application context"},
                      {"id": 2, "text": "Initialize beans"},
                      {"id": 3, "text": "Start embedded server"},
                      {"id": 4, "text": "Application ready"},
                      {"id": 5, "text": "Handle requests"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseOrderingQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());
        
        // First question
        Question question1 = questions.get(0);
        assertEquals("Order the Java compilation steps", question1.getQuestionText());
        assertEquals(Difficulty.EASY, question1.getDifficulty());
        
        JsonNode content1 = objectMapper.readTree(question1.getContent());
        assertEquals(3, content1.get("items").size());
        
        // Second question
        Question question2 = questions.get(1);
        assertEquals("Arrange the Spring Boot startup sequence", question2.getQuestionText());
        assertEquals(Difficulty.HARD, question2.getDifficulty());
        
        JsonNode content2 = objectMapper.readTree(question2.getContent());
        assertEquals(5, content2.get("items").size());
    }

    @Test
    void shouldHandleMissingDifficulty() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "First item"},
                      {"id": 2, "text": "Second item"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseOrderingQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        assertEquals(Difficulty.MEDIUM, questions.get(0).getDifficulty()); // Default difficulty
    }

    @Test
    void shouldHandleInvalidDifficulty() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "difficulty": "INVALID_DIFFICULTY",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "First item"},
                      {"id": 2, "text": "Second item"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseOrderingQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        assertEquals(Difficulty.MEDIUM, questions.get(0).getDifficulty()); // Default difficulty
    }

    @Test
    void shouldThrowExceptionForMissingContent() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING"
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingItems() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {}
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyItems() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": []
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForSingleItem() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Only one item"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForTooManyItems() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Item 1"},
                      {"id": 2, "text": "Item 2"},
                      {"id": 3, "text": "Item 3"},
                      {"id": 4, "text": "Item 4"},
                      {"id": 5, "text": "Item 5"},
                      {"id": 6, "text": "Item 6"},
                      {"id": 7, "text": "Item 7"},
                      {"id": 8, "text": "Item 8"},
                      {"id": 9, "text": "Item 9"},
                      {"id": 10, "text": "Item 10"},
                      {"id": 11, "text": "Item 11"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingItemId() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"text": "First item"},
                      {"id": 2, "text": "Second item"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNonIntegerItemId() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": "one", "text": "First item"},
                      {"id": 2, "text": "Second item"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingItemText() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1},
                      {"id": 2, "text": "Second item"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyItemText() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": ""},
                      {"id": 2, "text": "Second item"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForDuplicateItemIds() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items in order",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "First item"},
                      {"id": 1, "text": "Second item"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldHandleEmptyQuestionsArray() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": []
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseOrderingQuestions(contentNode);

        // Then
        assertTrue(questions.isEmpty());
    }

    @Test
    void shouldHandleMissingQuestionsArray() throws Exception {
        // Given
        String jsonContent = """
            {
              "otherField": "value"
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseOrderingQuestions(contentNode);

        // Then
        assertTrue(questions.isEmpty());
    }
} 