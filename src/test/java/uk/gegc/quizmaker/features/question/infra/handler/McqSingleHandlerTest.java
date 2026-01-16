package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class McqSingleHandlerTest {
    private McqSingleHandler handler;
    private ObjectMapper mapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new McqSingleHandler();
        mapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.MCQ_SINGLE);
    }

    @Test
    void validTwoOptions_exactlyOneCorrect() throws Exception {
        JsonNode payload = mapper.readTree("""
                  {"options":[
                    {"id":"a","text":"A","correct":false},
                    {"id":"b","text":"B","correct":true}
                  ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void missingOptions_throws() {
        JsonNode p = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void optionsNotArray_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"options":"nope"}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void tooFewOptions_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[{"id":"a","text":"A","correct":true}]}
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
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("exactly one correct"));
    }

    @Test
    void moreThanOneCorrect_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","text":"A","correct":true},
                    {"id":"b","text":"B","correct":true},
                    {"id":"c","text":"C","correct":false}
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
    void mediaOnlyOption_isAllowed() throws Exception {
        String assetId = UUID.randomUUID().toString();
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","media":{"assetId":"%s"},"correct":true},
                    {"id":"b","text":"B","correct":false}
                  ]}
                """.formatted(assetId));
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void invalidMediaAssetId_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"id":"a","media":{"assetId":"not-a-uuid"},"correct":true},
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
                    {"id":"a","text":"","correct":true},
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

    @Test
    void nullContent_throws() {
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(null)));
    }

    // Answer Validation Tests (doHandle method)
    @Test
    void doHandle_correctAnswer_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":false},
                  {"id":"b","text":"Option B","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionId\":\"b\"}");
        
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
                  {"id":"a","text":"Option A","correct":false},
                  {"id":"b","text":"Option B","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionId\":\"a\"}");
        
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
                  {"id":"a","text":"Option A","correct":false},
                  {"id":"b","text":"Option B","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionId\":\"\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingSelectedOptionId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":false},
                  {"id":"b","text":"Option B","correct":true}
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
                  {"id":"a","text":"Option A","correct":false},
                  {"id":"b","text":"Option B","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionId\":\"nonexistent\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_caseInsensitiveComparison_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"A","text":"Option A","correct":false},
                  {"id":"B","text":"Option B","correct":true}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionId\":\"b\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect()); // Should be false because "b" != "B"
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_multipleOptions_correctAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"options":[
                  {"id":"a","text":"Option A","correct":false},
                  {"id":"b","text":"Option B","correct":false},
                  {"id":"c","text":"Option C","correct":true},
                  {"id":"d","text":"Option D","correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedOptionId\":\"c\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.MCQ_SINGLE;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}
