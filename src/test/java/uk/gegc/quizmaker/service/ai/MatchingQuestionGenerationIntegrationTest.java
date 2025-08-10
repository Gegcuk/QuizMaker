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
import uk.gegc.quizmaker.service.ai.parser.QuestionParserFactory;
import uk.gegc.quizmaker.service.ai.parser.QuestionResponseParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Execution(ExecutionMode.CONCURRENT)
class MatchingQuestionGenerationIntegrationTest {

    @Autowired
    private QuestionParserFactory questionParserFactory;

    @Autowired
    private QuestionResponseParser questionResponseParser;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldGenerateMatchingQuestionsFromContent() throws Exception {
        // Given - Simulated AI response for MATCHING questions
        String aiResponse = """
            {
              "questions": [
                {
                  "questionText": "Match each fruit to its typical color",
                  "difficulty": "MEDIUM",
                  "type": "MATCHING",
                  "content": {
                    "left": [
                      {"id": 1, "text": "Apple", "matchId": 10},
                      {"id": 2, "text": "Banana", "matchId": 11},
                      {"id": 3, "text": "Grape", "matchId": 12}
                    ],
                    "right": [
                      {"id": 10, "text": "Red"},
                      {"id": 11, "text": "Yellow"},
                      {"id": 12, "text": "Purple"}
                    ]
                  },
                  "hint": "Think of common varieties",
                  "explanation": "Apples are often red, bananas yellow, grapes purple"
                }
              ]
            }
            """;

        JsonNode contentNode = objectMapper.readTree(aiResponse);

        // When
        List<Question> questions = questionParserFactory.parseQuestions(contentNode, QuestionType.MATCHING);

        // Then
        assertEquals(1, questions.size());
        Question q = questions.get(0);
        assertEquals(QuestionType.MATCHING, q.getType());
        assertEquals("Match each fruit to its typical color", q.getQuestionText());
        assertEquals(Difficulty.MEDIUM, q.getDifficulty());
        assertEquals("Think of common varieties", q.getHint());
        assertEquals("Apples are often red, bananas yellow, grapes purple", q.getExplanation());

        JsonNode content = objectMapper.readTree(q.getContent());
        assertTrue(content.has("left"));
        assertTrue(content.has("right"));
        assertEquals(3, content.get("left").size());
        assertEquals(3, content.get("right").size());

        // Verify a couple of mappings
        assertEquals(10, content.get("left").get(0).get("matchId").asInt());
        assertEquals("Red", content.get("right").get(0).get("text").asText());
    }

    @Test
    void shouldValidateMatchingQuestionStructure() {
        // Given - Invalid structure: left item missing matchId
        String invalidResponse = """
            {
              "questions": [
                {
                  "questionText": "Match A to B",
                  "type": "MATCHING",
                  "content": {
                    "left": [ {"id": 1, "text": "A"} ],
                    "right": [ {"id": 10, "text": "B"} ]
                  }
                }
              ]
            }
            """;

        assertThrows(Exception.class, () -> {
            // Go through the full response parser so handler-based validation runs
            questionResponseParser.parseQuestionsFromAIResponse(invalidResponse, QuestionType.MATCHING);
        });
    }
}


