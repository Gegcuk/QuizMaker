package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.ai.infra.parser.FillGapQuestionParser;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class FillGapQuestionParserTest {

    private FillGapQuestionParser parser;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        parser = new FillGapQuestionParser();
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldParseValidFillGapQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence with the missing word",
                  "difficulty": "MEDIUM",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ programming language",
                    "gaps": [
                      {"id": 1, "answer": "object-oriented"}
                    ]
                  },
                  "hint": "Think about Java's main paradigm",
                  "explanation": "Java is primarily an object-oriented programming language"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseFillGapQuestions(contentNode);

        // Then
        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals("Complete the sentence with the missing word", question.getQuestionText());
        assertEquals(QuestionType.FILL_GAP, question.getType());
        assertEquals(Difficulty.MEDIUM, question.getDifficulty());
        assertEquals("Think about Java's main paradigm", question.getHint());
        assertEquals("Java is primarily an object-oriented programming language", question.getExplanation());
        
        // Verify content structure
        JsonNode content = objectMapper.readTree(question.getContent());
        assertEquals("Java is a ___ programming language", content.get("text").asText());
        assertTrue(content.has("gaps"));
        assertEquals(1, content.get("gaps").size());
        assertEquals(1, content.get("gaps").get(0).get("id").asInt());
        assertEquals("object-oriented", content.get("gaps").get(0).get("answer").asText());
    }

    @Test
    void shouldParseMultipleFillGapQuestions() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
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
                  }
                },
                {
                  "questionText": "Complete the sentence about REST",
                  "difficulty": "EASY",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "REST stands for ___ State Transfer",
                    "gaps": [
                      {"id": 1, "answer": "Representational"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseFillGapQuestions(contentNode);

        // Then
        assertEquals(2, questions.size());
        
        // First question
        Question question1 = questions.get(0);
        assertEquals("Fill in the blanks about Spring Framework", question1.getQuestionText());
        assertEquals(Difficulty.HARD, question1.getDifficulty());
        
        JsonNode content1 = objectMapper.readTree(question1.getContent());
        assertEquals(2, content1.get("gaps").size());
        
        // Second question
        Question question2 = questions.get(1);
        assertEquals("Complete the sentence about REST", question2.getQuestionText());
        assertEquals(Difficulty.EASY, question2.getDifficulty());
        
        JsonNode content2 = objectMapper.readTree(question2.getContent());
        assertEquals(1, content2.get("gaps").size());
    }

    @Test
    void shouldHandleMissingDifficulty() throws Exception {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language",
                    "gaps": [
                      {"id": 1, "answer": "programming"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseFillGapQuestions(contentNode);

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
                  "questionText": "Complete the sentence",
                  "difficulty": "INVALID_DIFFICULTY",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language",
                    "gaps": [
                      {"id": 1, "answer": "programming"}
                    ]
                  }
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(jsonContent);

        // When
        List<Question> questions = parser.parseFillGapQuestions(contentNode);

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
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP"
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingText() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "gaps": [
                      {"id": 1, "answer": "programming"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyText() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "",
                    "gaps": [
                      {"id": 1, "answer": "programming"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingGaps() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language"
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyGaps() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language",
                    "gaps": []
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingGapId() {
        // Given
        String jsonContent = """
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
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForNonIntegerGapId() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language",
                    "gaps": [
                      {"id": "one", "answer": "programming"}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForMissingGapAnswer() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language",
                    "gaps": [
                      {"id": 1}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
        });
    }

    @Test
    void shouldThrowExceptionForEmptyGapAnswer() {
        // Given
        String jsonContent = """
            {
              "questions": [
                {
                  "questionText": "Complete the sentence",
                  "type": "FILL_GAP",
                  "content": {
                    "text": "Java is a ___ language",
                    "gaps": [
                      {"id": 1, "answer": ""}
                    ]
                  }
                }
              ]
            }
            """;

        // When & Then
        assertThrows(AIResponseParseException.class, () -> {
            JsonNode contentNode = objectMapper.readTree(jsonContent);
            parser.parseFillGapQuestions(contentNode);
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
        List<Question> questions = parser.parseFillGapQuestions(contentNode);

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
        List<Question> questions = parser.parseFillGapQuestions(contentNode);

        // Then
        assertTrue(questions.isEmpty());
    }
} 