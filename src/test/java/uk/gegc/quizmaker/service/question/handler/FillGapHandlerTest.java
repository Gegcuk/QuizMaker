package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class FillGapHandlerTest {
    private FillGapHandler handler;
    private ObjectMapper mapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new FillGapHandler();
        mapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.FILL_GAP);
    }

    @Test
    void validContent_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void missingText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"gaps":[{"id":1,"answer":"sky"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"","gaps":[{"id":1,"answer":"sky"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingGaps_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"The ___ is blue"}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyGaps_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingGapAnswer_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyGapAnswer_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":""}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void duplicateIds_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"The ___ is ___","gaps":[{"id":1,"answer":"sky"},{"id":1,"answer":"blue"}]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("duplicate ID: 1"));
    }

    // Answer Validation Tests (doHandle method)
    @Test
    void doHandle_correctAnswer_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"sky\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_incorrectAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"ocean\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_caseInsensitiveMatch_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"Sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"sky\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_whitespaceInsensitiveMatch_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer\":\"sky\"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"  sky  \"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_multipleGaps_allCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is ___","gaps":[{"id":1,"answer":"sky"},{"id":2,"answer":"blue"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"sky\"},{\"gapId\":2,\"answer\":\"blue\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_multipleGaps_partialCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is ___","gaps":[{"id":1,"answer":"sky"},{"id":2,"answer":"blue"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"sky\"},{\"gapId\":2,\"answer\":\"red\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingAnswersField_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.createObjectNode();
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nonexistentGapId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":999,\"answer\":\"sky\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_emptyAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_blankAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"   \"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingGapId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"answer\":\"sky\"}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingAnswerField_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is blue","gaps":[{"id":1,"answer":"sky"}]}
                """);
        JsonNode response = mapper.readTree("{\"answers\":[{\"gapId\":1}]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_orderDoesNotMatter() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"text":"The ___ is ___","gaps":[{"id":1,"answer":"sky"},{"id":2,"answer":"blue"}]}
                """);
        JsonNode response1 = mapper.readTree("{\"answers\":[{\"gapId\":1,\"answer\":\"sky\"},{\"gapId\":2,\"answer\":\"blue\"}]}");
        JsonNode response2 = mapper.readTree("{\"answers\":[{\"gapId\":2,\"answer\":\"blue\"},{\"gapId\":1,\"answer\":\"sky\"}]}");
        
        // When
        Answer answer1 = handler.doHandle(testAttempt, testQuestion, content, response1);
        Answer answer2 = handler.doHandle(testAttempt, testQuestion, content, response2);
        
        // Then
        assertTrue(answer1.getIsCorrect());
        assertTrue(answer2.getIsCorrect());
        assertEquals(1.0, answer1.getScore());
        assertEquals(1.0, answer2.getScore());
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.FILL_GAP;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}