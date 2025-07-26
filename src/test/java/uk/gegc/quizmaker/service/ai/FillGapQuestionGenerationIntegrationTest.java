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
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.ai.parser.FillGapQuestionParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Execution(ExecutionMode.SAME_THREAD)
class FillGapQuestionGenerationIntegrationTest {

    @Autowired
    private FillGapQuestionParser fillGapQuestionParser;

    @Autowired
    private ObjectMapper objectMapper;

    private String testChunkContent;

    @BeforeEach
    void setUp() {
        testChunkContent = """
            Java is a high-level, class-based, object-oriented programming language that is designed to have as few implementation dependencies as possible. It is a general-purpose programming language intended to let programmers write once, run anywhere (WORA), meaning that compiled Java code can run on all platforms that support Java without the need to recompile.
            
            Spring Framework is an application framework and inversion of control container for the Java platform. The framework's core features can be used by any Java application, but there are extensions for building web applications on top of the Java EE (Enterprise Edition) platform.
            
            REST (Representational State Transfer) is an architectural style that defines a set of constraints to be used for creating web services. Web services that conform to the REST architectural style, called RESTful web services, provide interoperability between computer systems on the internet.
            """;
    }

    @Test
    void shouldParseValidFillGapQuestionsFromAIResponse() throws Exception {
        // Given - Simulated AI response for FILL_GAP questions
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence about Java programming language",
                  "difficulty": "MEDIUM",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ programming language that follows the ___ principle",
                    "gaps": [
                      {"id": 1, "answer": "object-oriented"},
                      {"id": 2, "answer": "WORA"}
                    ]
                  },
                  "hint": "Think about Java's main characteristics and its famous slogan",
                  "explanation": "Java is an object-oriented programming language that follows the WORA (Write Once, Run Anywhere) principle"
                },
                {
                  "questionText": "Fill in the blanks about Spring Framework",
                  "difficulty": "HARD",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Spring Framework provides ___ and ___ for Java applications",
                    "gaps": [
                      {"id": 1, "answer": "dependency injection"},
                      {"id": 2, "answer": "inversion of control"}
                    ]
                  },
                  "hint": "Consider the core features that Spring Framework is known for",
                  "explanation": "Spring Framework provides dependency injection and inversion of control for Java applications"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = fillGapQuestionParser.parseFillGapQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());

        // Verify first question
        Question question1 = questions.get(0);
        assertEquals("Complete the sentence about Java programming language", question1.getQuestionText());
        assertEquals(QuestionType.FILL_GAP, question1.getType());
        assertEquals(Difficulty.MEDIUM, question1.getDifficulty());
        assertEquals("Think about Java's main characteristics and its famous slogan", question1.getHint());
        assertEquals("Java is an object-oriented programming language that follows the WORA (Write Once, Run Anywhere) principle", question1.getExplanation());

        // Verify first question content structure
        JsonNode content1 = objectMapper.readTree(question1.getContent());
        assertEquals("Java is a ___ programming language that follows the ___ principle", content1.get("text").asText());
        assertEquals(2, content1.get("gaps").size());
        assertEquals(1, content1.get("gaps").get(0).get("id").asInt());
        assertEquals("object-oriented", content1.get("gaps").get(0).get("answer").asText());
        assertEquals(2, content1.get("gaps").get(1).get("id").asInt());
        assertEquals("WORA", content1.get("gaps").get(1).get("answer").asText());

        // Verify second question
        Question question2 = questions.get(1);
        assertEquals("Fill in the blanks about Spring Framework", question2.getQuestionText());
        assertEquals(Difficulty.HARD, question2.getDifficulty());

        // Verify second question content structure
        JsonNode content2 = objectMapper.readTree(question2.getContent());
        assertEquals("Spring Framework provides ___ and ___ for Java applications", content2.get("text").asText());
        assertEquals(2, content2.get("gaps").size());
        assertEquals("dependency injection", content2.get("gaps").get(0).get("answer").asText());
        assertEquals("inversion of control", content2.get("gaps").get(1).get("answer").asText());
    }

    @Test
    void shouldHandleSingleGapQuestions() throws Exception {
        // Given - AI response with single gap questions
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "What does REST stand for?",
                  "difficulty": "EASY",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "REST stands for ___ State Transfer",
                    "gaps": [
                      {"id": 1, "answer": "Representational"}
                    ]
                  },
                  "hint": "Think about the full name of REST",
                  "explanation": "REST stands for Representational State Transfer"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = fillGapQuestionParser.parseFillGapQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals(QuestionType.FILL_GAP, question.getType());
        assertEquals(Difficulty.EASY, question.getDifficulty());

        JsonNode content = objectMapper.readTree(question.getContent());
        assertEquals("REST stands for ___ State Transfer", content.get("text").asText());
        assertEquals(1, content.get("gaps").size());
        assertEquals("Representational", content.get("gaps").get(0).get("answer").asText());
    }

    @Test
    void shouldHandleQuestionsWithMultipleGaps() throws Exception {
        // Given - AI response with multiple gaps
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Complete the Java programming description",
                  "difficulty": "HARD",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ programming language that is designed to have ___ implementation dependencies as possible",
                    "gaps": [
                      {"id": 1, "answer": "high-level"},
                      {"id": 2, "answer": "few"}
                    ]
                  },
                  "hint": "Consider Java's design philosophy and level of abstraction",
                  "explanation": "Java is a high-level programming language that is designed to have few implementation dependencies as possible"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = fillGapQuestionParser.parseFillGapQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals(QuestionType.FILL_GAP, question.getType());

        JsonNode content = objectMapper.readTree(question.getContent());
        assertEquals("Java is a ___ programming language that is designed to have ___ implementation dependencies as possible", content.get("text").asText());
        assertEquals(2, content.get("gaps").size());
        
        // Verify gap order and content
        assertEquals(1, content.get("gaps").get(0).get("id").asInt());
        assertEquals("high-level", content.get("gaps").get(0).get("answer").asText());
        assertEquals(2, content.get("gaps").get(1).get("id").asInt());
        assertEquals("few", content.get("gaps").get(1).get("answer").asText());
    }

    @Test
    void shouldHandleQuestionsWithoutHintsAndExplanations() throws Exception {
        // Given - AI response without optional fields
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "difficulty": "MEDIUM",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Spring Framework is an ___ framework for Java",
                    "gaps": [
                      {"id": 1, "answer": "application"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = fillGapQuestionParser.parseFillGapQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertNull(question.getHint()); // Should be null when not provided
        assertNull(question.getExplanation()); // Should be null when not provided
        assertEquals(Difficulty.MEDIUM, question.getDifficulty());
    }

    @Test
    void shouldHandleQuestionsWithDefaultDifficulty() throws Exception {
        // Given - AI response without difficulty field
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java follows the ___ principle",
                    "gaps": [
                      {"id": 1, "answer": "WORA"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = fillGapQuestionParser.parseFillGapQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals(Difficulty.MEDIUM, question.getDifficulty()); // Should default to MEDIUM
    }

    @Test
    void shouldValidateGapStructureCorrectly() {
        // Given - Invalid AI response with missing gap ID
        String invalidResponse = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language",
                    "gaps": [
                      {"answer": "programming"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(Exception.class, () -> {
            JsonNode contentNode = objectMapper.readTree(invalidResponse);
            fillGapQuestionParser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldHandleEmptyQuestionsArray() throws Exception {
        // Given - AI response with empty questions array
        String aiResponse = """
            {
              "questions": []
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = fillGapQuestionParser.parseFillGapQuestions(contentNode);

        // Then
        assertTrue(questions.isEmpty());
    }
} 