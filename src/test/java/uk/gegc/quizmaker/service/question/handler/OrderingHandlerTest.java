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
class OrderingHandlerTest {
    private OrderingHandler handler;
    private ObjectMapper mapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new OrderingHandler();
        mapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.ORDERING);
    }

    @Test
    void validTwoItems_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"items":[{"id":1,"text":"First"},{"id":2,"text":"Second"}]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void validMultipleItems_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"},
                  {"id":3,"text":"Third"}
                ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void missingItems_throws() {
        JsonNode p = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyItems_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"items":[]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void singleItem_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"items":[{"id":1,"text":"Only one"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void tooManyItems_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"Item 1"},{"id":2,"text":"Item 2"},{"id":3,"text":"Item 3"},
                  {"id":4,"text":"Item 4"},{"id":5,"text":"Item 5"},{"id":6,"text":"Item 6"},
                  {"id":7,"text":"Item 7"},{"id":8,"text":"Item 8"},{"id":9,"text":"Item 9"},
                  {"id":10,"text":"Item 10"},{"id":11,"text":"Item 11"}
                ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingItemText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"items":[{"id":1},{"id":2,"text":"Second"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyItemText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"items":[{"id":1,"text":""},{"id":2,"text":"Second"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankItemText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"items":[{"id":1,"text":"   "},{"id":2,"text":"Second"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void duplicateIds_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"items":[{"id":1,"text":"First"},{"id":1,"text":"Second"}]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("duplicate ID: 1"));
    }

    // Answer Validation Tests (doHandle method)
    @Test
    void doHandle_correctOrder_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"},
                  {"id":3,"text":"Third"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[1,2,3]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_incorrectOrder_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"},
                  {"id":3,"text":"Third"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[3,1,2]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_partialOrder_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"},
                  {"id":3,"text":"Third"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[1,2]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_extraItems_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[1,2,999]}");
        
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
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingOrderedItemIds_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"}
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
    void doHandle_nonexistentItemId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[999]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_duplicateItemIds_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[1,1]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_reverseOrder_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"},
                  {"id":3,"text":"Third"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[3,2,1]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_twoItems_correctOrder() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[1,2]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_twoItems_incorrectOrder() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[2,1]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_fiveItems_correctOrder() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"},
                  {"id":3,"text":"Third"},
                  {"id":4,"text":"Fourth"},
                  {"id":5,"text":"Fifth"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[1,2,3,4,5]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_fiveItems_partialCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"items":[
                  {"id":1,"text":"First"},
                  {"id":2,"text":"Second"},
                  {"id":3,"text":"Third"},
                  {"id":4,"text":"Fourth"},
                  {"id":5,"text":"Fifth"}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"orderedItemIds\":[1,2,4,3,5]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.ORDERING;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}