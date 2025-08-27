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
class HotspotHandlerTest {
    private HotspotHandler handler;
    private ObjectMapper mapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new HotspotHandler();
        mapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.HOTSPOT);
    }

    @Test
    void validTwoRegions_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void validMultipleRegions_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void missingImageUrl_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"regions":[{"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyImageUrl_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"","regions":[{"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingRegions_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x"}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyRegions_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void singleRegion_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[{"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void tooManyRegions_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false},
                  {"id":3,"x":100,"y":200,"width":300,"height":400,"correct":true},
                  {"id":4,"x":1000,"y":2000,"width":3000,"height":4000,"correct":false},
                  {"id":5,"x":10000,"y":20000,"width":30000,"height":40000,"correct":true},
                  {"id":6,"x":100000,"y":200000,"width":300000,"height":400000,"correct":false},
                  {"id":7,"x":1000000,"y":2000000,"width":3000000,"height":4000000,"correct":true}
                ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingIdInRegion_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[{"x":1,"y":2,"width":3,"height":4,"correct":true},{"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("must have an 'id' field"));
    }

    @Test
    void missingCorrectInRegion_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[{"id":1,"x":1,"y":2,"width":3,"height":4},{"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("must have a 'correct' field"));
    }

    @Test
    void noCorrectRegion_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":false},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void duplicateIds_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":1,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("duplicate ID: 1"));
    }

    @Test
    void negativeCoordinates_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":-1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void nonIntegerCoordinates_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":"bad","y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    // Answer Validation Tests (doHandle method)
    @Test
    void doHandle_correctRegion_returnsCorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":1}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_incorrectRegion_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":2}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_multipleCorrectRegions_correctAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":true},
                  {"id":3,"x":100,"y":200,"width":300,"height":400,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":1}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_multipleCorrectRegions_anotherCorrectAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":true},
                  {"id":3,"x":100,"y":200,"width":300,"height":400,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":2}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_missingResponse_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
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
    void doHandle_nonexistentRegionId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":999}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nullSelectedRegionId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":null}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_nonIntegerSelectedRegionId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":\"not_a_number\"}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_twoRegions_correctAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":1}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_twoRegions_incorrectAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":true},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":2}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_fiveRegions_correctAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":false},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":true},
                  {"id":3,"x":100,"y":200,"width":300,"height":400,"correct":false},
                  {"id":4,"x":1000,"y":2000,"width":3000,"height":4000,"correct":false},
                  {"id":5,"x":10000,"y":20000,"width":30000,"height":40000,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":2}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_fiveRegions_incorrectAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"imageUrl":"http://x","regions":[
                  {"id":1,"x":1,"y":2,"width":3,"height":4,"correct":false},
                  {"id":2,"x":10,"y":20,"width":30,"height":40,"correct":true},
                  {"id":3,"x":100,"y":200,"width":300,"height":400,"correct":false},
                  {"id":4,"x":1000,"y":2000,"width":3000,"height":4000,"correct":false},
                  {"id":5,"x":10000,"y":20000,"width":30000,"height":40000,"correct":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedRegionId\":1}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.HOTSPOT;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}