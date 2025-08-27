package uk.gegc.quizmaker.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.ai.infra.parser.OrderingQuestionParser;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.CONCURRENT)
class OrderingQuestionGenerationIntegrationTest {

    @Autowired
    private OrderingQuestionParser orderingQuestionParser;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldGenerateOrderingQuestionsFromContent() throws Exception {
        // Given - Sample content that would be used for ORDERING questions
        String sampleContent = """
            Spring Boot Application Lifecycle:
            1. Application startup begins
            2. Spring context is loaded
            3. Beans are initialized
            4. Embedded server starts
            5. Application is ready to handle requests
            
            Java Compilation Process:
            1. Write Java source code (.java files)
            2. Compile to bytecode (.class files)
            3. Load classes into JVM
            4. Execute bytecode
            """;

        // When - Simulate AI response for ORDERING questions
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Arrange the Spring Boot application startup sequence in the correct order",
                  "difficulty": "MEDIUM",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Application startup begins"},
                      {"id": 2, "text": "Spring context is loaded"},
                      {"id": 3, "text": "Beans are initialized"},
                      {"id": 4, "text": "Embedded server starts"},
                      {"id": 5, "text": "Application is ready to handle requests"}
                    ]
                  },
                  "hint": "Think about the logical sequence of application initialization",
                  "explanation": "The correct order follows the Spring Boot application lifecycle from startup to readiness"
                },
                {
                  "questionText": "Order the Java compilation and execution steps correctly",
                  "difficulty": "EASY",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Write Java source code (.java files)"},
                      {"id": 2, "text": "Compile to bytecode (.class files)"},
                      {"id": 3, "text": "Load classes into JVM"},
                      {"id": 4, "text": "Execute bytecode"}
                    ]
                  },
                  "hint": "Consider the transformation from source code to execution",
                  "explanation": "This follows the standard Java compilation and execution pipeline"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = orderingQuestionParser.parseOrderingQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());

        // Verify first question (Spring Boot lifecycle)
        Question springBootQuestion = questions.get(0);
        assertEquals("Arrange the Spring Boot application startup sequence in the correct order", 
                     springBootQuestion.getQuestionText());
        assertEquals(QuestionType.ORDERING, springBootQuestion.getType());
        assertEquals(Difficulty.MEDIUM, springBootQuestion.getDifficulty());
        assertEquals("Think about the logical sequence of application initialization", springBootQuestion.getHint());
        assertEquals("The correct order follows the Spring Boot application lifecycle from startup to readiness", 
                     springBootQuestion.getExplanation());

        // Verify content structure for first question
        JsonNode content1 = objectMapper.readTree(springBootQuestion.getContent());
        assertTrue(content1.has("items"));
        assertEquals(5, content1.get("items").size());
        
        // Verify items are in correct order
        assertEquals(1, content1.get("items").get(0).get("id").asInt());
        assertEquals("Application startup begins", content1.get("items").get(0).get("text").asText());
        assertEquals(5, content1.get("items").get(4).get("id").asInt());
        assertEquals("Application is ready to handle requests", content1.get("items").get(4).get("text").asText());

        // Verify second question (Java compilation)
        Question javaQuestion = questions.get(1);
        assertEquals("Order the Java compilation and execution steps correctly", javaQuestion.getQuestionText());
        assertEquals(QuestionType.ORDERING, javaQuestion.getType());
        assertEquals(Difficulty.EASY, javaQuestion.getDifficulty());
        assertEquals("Consider the transformation from source code to execution", javaQuestion.getHint());
        assertEquals("This follows the standard Java compilation and execution pipeline", javaQuestion.getExplanation());

        // Verify content structure for second question
        JsonNode content2 = objectMapper.readTree(javaQuestion.getContent());
        assertTrue(content2.has("items"));
        assertEquals(4, content2.get("items").size());
        
        // Verify items are in correct order
        assertEquals(1, content2.get("items").get(0).get("id").asInt());
        assertEquals("Write Java source code (.java files)", content2.get("items").get(0).get("text").asText());
        assertEquals(4, content2.get("items").get(3).get("id").asInt());
        assertEquals("Execute bytecode", content2.get("items").get(3).get("text").asText());
    }

    @Test
    void shouldHandleComplexOrderingScenarios() throws Exception {
        // Given - Complex ordering scenario with multiple criteria
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Arrange the software development lifecycle phases in the correct sequence",
                  "difficulty": "HARD",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "Requirements gathering and analysis"},
                      {"id": 2, "text": "System design and architecture"},
                      {"id": 3, "text": "Implementation and coding"},
                      {"id": 4, "text": "Testing and quality assurance"},
                      {"id": 5, "text": "Deployment and release"},
                      {"id": 6, "text": "Maintenance and updates"},
                      {"id": 7, "text": "Monitoring and feedback collection"}
                    ]
                  },
                  "hint": "Consider the logical flow from initial planning to ongoing maintenance",
                  "explanation": "This represents the complete software development lifecycle from conception to maintenance"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = orderingQuestionParser.parseOrderingQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals("Arrange the software development lifecycle phases in the correct sequence", 
                     question.getQuestionText());
        assertEquals(QuestionType.ORDERING, question.getType());
        assertEquals(Difficulty.HARD, question.getDifficulty());

        // Verify complex content structure
        JsonNode content = objectMapper.readTree(question.getContent());
        assertTrue(content.has("items"));
        assertEquals(7, content.get("items").size());

        // Verify all items have unique IDs and proper text
        for (int i = 0; i < 7; i++) {
            JsonNode item = content.get("items").get(i);
            assertEquals(i + 1, item.get("id").asInt());
            assertFalse(item.get("text").asText().trim().isEmpty());
        }
    }

    @Test
    void shouldValidateOrderingQuestionStructure() {
        // Given - Invalid structure with missing required fields
        String invalidResponse = """
            {
              "questions": [
                {
                  "questionText": "Arrange the items",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"text": "Item without ID"},
                      {"id": 2, "text": "Valid item"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(Exception.class, () -> {
            JsonNode contentNode = objectMapper.readTree(invalidResponse);
            orderingQuestionParser.parseOrderingQuestions(contentNode);
        });
    }

    @Test
    void shouldHandleEdgeCasesGracefully() throws Exception {
        // Given - Edge case with minimum valid items
        String edgeCaseResponse = """
            {
              "questions": [
                {
                  "questionText": "Arrange the two steps",
                  "difficulty": "EASY",
                  "type": "ORDERING",
                  "content": {
                    "items": [
                      {"id": 1, "text": "First step"},
                      {"id": 2, "text": "Second step"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(edgeCaseResponse);

        // When
        List<Question> questions = orderingQuestionParser.parseOrderingQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals(QuestionType.ORDERING, question.getType());
        assertEquals(Difficulty.EASY, question.getDifficulty());

        JsonNode content = objectMapper.readTree(question.getContent());
        assertEquals(2, content.get("items").size());
    }
} 