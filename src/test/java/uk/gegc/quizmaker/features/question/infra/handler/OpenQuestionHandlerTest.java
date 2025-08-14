package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.features.question.infra.handler.OpenQuestionHandler;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class OpenQuestionHandlerTest {
    private OpenQuestionHandler handler;
    private ObjectMapper mapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new OpenQuestionHandler();
        mapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.OPEN);
    }

    @Test
    void validAnswer_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"answer":"This is a valid answer"}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void missingAnswer_throws() {
        JsonNode p = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyAnswer_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"answer":""}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankAnswer_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"answer":"   "}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void nullContent_throws() {
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(null)));
    }

    // Answer Validation Tests (doHandle method)
    @Test
    void doHandle_exactMatch_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"The correct answer\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_caseInsensitiveMatch_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The Correct Answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"the correct answer\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_whitespaceInsensitiveMatch_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"  The correct answer  \"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_incorrectAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"Wrong answer\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_partialMatch_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"correct answer\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_emptyResponse_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_blankResponse_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"   \"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingAnswerField_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.createObjectNode();
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nullAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":null}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nonStringAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The correct answer\"}");
        JsonNode response = mapper.readTree("{\"answer\":123}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_longAnswer() throws Exception {
        // Given
        String longAnswer = "This is a very long answer that contains many words and should be handled correctly by the system. It includes various punctuation marks and different types of content.";
        JsonNode content = mapper.readTree("{\"answer\":\"" + longAnswer + "\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"" + longAnswer + "\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_specialCharacters() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"Answer with special chars: !@#$%^&*()_+-=[]{}|;':\\\",./<>?\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"Answer with special chars: !@#$%^&*()_+-=[]{}|;':\\\",./<>?\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_numbersAndText() throws Exception {
        // Given
        JsonNode content = mapper.readTree("{\"answer\":\"The answer is 42\"}");
        JsonNode response = mapper.readTree("{\"answer\":\"The answer is 42\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.OPEN;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}