package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class McqMultiHandlerTest {
    private McqMultiHandler handler;
    private ObjectMapper mapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new McqMultiHandler();
        mapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.MCQ_MULTI);
    }

    @Test
    void validTwoOptions_oneCorrect() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","text":"A","correct":true},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void validMultipleCorrect() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","text":"A","correct":true},
                    {"id":"b","text":"B","correct":true},
                    {"id":"c","text":"C","correct":false}
                  ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingOptions_throws() {
        JsonNode p = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void tooFewOptions_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[{"id":"a","text":"A","correct":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void noCorrect_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","text":"A","correct":false},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","correct":true},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","text":" ","correct":true},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingId_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"text":"A","correct":true},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("must have an 'id' field"));
    }

    @Test
    void blankId_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"","text":"A","correct":true},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("must be a non-empty string"));
    }

    @Test
    void nonStringId_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":123,"text":"A","correct":true},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("must be a non-empty string"));
    }

    @Test
    void duplicateIds_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","text":"A","correct":true},
                    {"id":"a","text":"B","correct":false}
                  ]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("duplicate ID: a"));
    }

    // Answer Validation Tests (doHandle method)
    @Test
    void doHandle_correctAnswer_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[\"a\",\"c\"]}");
        
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
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[\"a\",\"b\"]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_partialAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[\"a\"]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_extraAnswer_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[\"a\",\"c\",\"b\"]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_emptyResponse_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingSelectedOptionIds_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false}
                ]}
                """);
        JsonNode response = mapper.createObjectNode();
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nonexistentOptionId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[\"nonexistent\"]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_singleCorrectAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[\"a\"]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_noCorrectAnswers_selected() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionIds\":[\"b\",\"c\"]}");
        
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
                {"options":[
                  {"id":"a","text":"Option A","correct":true},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":true}
                ]}
                """);
        JsonNode response1 = mapper.readTree("{\"selectedOptionIds\":[\"a\",\"c\"]}");
        JsonNode response2 = mapper.readTree("{\"selectedOptionIds\":[\"c\",\"a\"]}");
        
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
            return QuestionType.MCQ_MULTI;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}