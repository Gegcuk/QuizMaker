package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
public class TrueFalseHandlerTest {

    private TrueFalseHandler handler;
    private ObjectMapper objectMapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new TrueFalseHandler();
        objectMapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.TRUE_FALSE);
    }

    @Test
    void validTrue_doesNotThrow() throws Exception {
        JsonNode node = objectMapper.readTree("{\"answer\":true}");
        assertDoesNotThrow(() -> handler.validateContent(new FakeRequest(node)));
    }

    @Test
    void validFalse_doesNotThrow() throws Exception {
        JsonNode node = objectMapper.readTree("{\"answer\":false}");
        assertDoesNotThrow(() -> handler.validateContent(new FakeRequest(node)));
    }

    @Test
    void missingAnswer_throws() {
        JsonNode node = objectMapper.createObjectNode();
        ValidationException exception = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeRequest(node)));
        assertTrue(exception.getMessage().contains("requires an 'answer' boolean"));
    }

    @Test
    void wrongTypeAnswer_throws() throws Exception {
        JsonNode node = objectMapper.readTree("{\"answer\":\"oops\"}");
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeRequest(node)));
    }

    // Answer Validation Tests (doHandle method)
    @Test
    void doHandle_correctTrueAnswer_returnsCorrect() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        JsonNode response = objectMapper.readTree("{\"answer\":true}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_correctFalseAnswer_returnsCorrect() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":false}");
        JsonNode response = objectMapper.readTree("{\"answer\":false}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_incorrectAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        JsonNode response = objectMapper.readTree("{\"answer\":false}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingAnswerField_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        JsonNode response = objectMapper.createObjectNode();
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // When
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nonBooleanAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        JsonNode response = objectMapper.readTree("{\"answer\":\"maybe\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nullAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        JsonNode response = objectMapper.readTree("{\"answer\":null}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_oppositeAnswers() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        JsonNode response = objectMapper.readTree("{\"answer\":false}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_bothFalse() throws Exception {
        // Given
        JsonNode content = objectMapper.readTree("{\"answer\":false}");
        JsonNode response = objectMapper.readTree("{\"answer\":false}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    record FakeRequest(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.TRUE_FALSE;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}
