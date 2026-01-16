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
class ComplianceHandlerTest {
    private ComplianceHandler handler;
    private ObjectMapper mapper;
    private Attempt testAttempt;
    private Question testQuestion;

    @BeforeEach
    void setUp() {
        handler = new ComplianceHandler();
        mapper = new ObjectMapper();
        
        // Setup test attempt and question
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setStartedAt(Instant.now());
        
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.COMPLIANCE);
    }

    @Test
    void validTwoStatements_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"statements":[{"id":1,"text":"T","compliant":true},{"id":2,"text":"F","compliant":false}]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void validMultipleStatements_doesNotThrow() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test
    void missingStatements_throws() {
        JsonNode p = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyStatements_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void singleStatement_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"text":"Only one","compliant":true}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void tooManyStatements_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"S1","compliant":true},{"id":2,"text":"S2","compliant":false},
                  {"id":3,"text":"S3","compliant":true},{"id":4,"text":"S4","compliant":false},
                  {"id":5,"text":"S5","compliant":true},{"id":6,"text":"S6","compliant":false},
                  {"id":7,"text":"S7","compliant":true}
                ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingStatementText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"compliant":true},{"id":2,"text":"Second","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void mediaOnlyStatement_isAllowed() throws Exception {
        String assetId = UUID.randomUUID().toString();
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"media":{"assetId":"%s"},"compliant":true},{"id":2,"text":"Second","compliant":false}]}
                """.formatted(assetId));
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void invalidMediaAssetId_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"media":{"assetId":"not-a-uuid"},"compliant":true},{"id":2,"text":"Second","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyStatementText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"text":"","compliant":true},{"id":2,"text":"Second","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankStatementText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"text":"   ","compliant":true},{"id":2,"text":"Second","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingCompliant_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"text":"First"},{"id":2,"text":"Second","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void nonBooleanCompliant_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"text":"First","compliant":"yes"},{"id":2,"text":"Second","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void noCompliantStatements_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":false},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingIdInStatement_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"text":"First","compliant":true},{"id":2,"text":"Second","compliant":false}]}
                """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("must have an 'id' field"));
    }

    @Test
    void duplicateIds_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"statements":[{"id":1,"text":"First","compliant":true},{"id":1,"text":"Second","compliant":false}]}
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
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[1]}");
        
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
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[2]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_multipleCompliantStatements_correctAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":true},
                  {"id":3,"text":"Statement 3","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[1,2]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_multipleCompliantStatements_partialAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":true},
                  {"id":3,"text":"Statement 3","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[1]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_multipleCompliantStatements_extraAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":true},
                  {"id":3,"text":"Statement 3","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[1,2,3]}");
        
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
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_missingSelectedStatementIds_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
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
    void doHandle_nonexistentStatementId_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[999]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    @Test
    void doHandle_duplicateStatementIds_returnsIncorrect() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[1,1]}");
        
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
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":true},
                  {"id":3,"text":"Statement 3","compliant":false}
                ]}
                """);
        JsonNode response1 = mapper.readTree("{\"selectedStatementIds\":[1,2]}");
        JsonNode response2 = mapper.readTree("{\"selectedStatementIds\":[2,1]}");
        
        // When
        Answer answer1 = handler.doHandle(testAttempt, testQuestion, content, response1);
        Answer answer2 = handler.doHandle(testAttempt, testQuestion, content, response2);
        
        // Then
        assertTrue(answer1.getIsCorrect());
        assertTrue(answer2.getIsCorrect());
        assertEquals(1.0, answer1.getScore());
        assertEquals(1.0, answer2.getScore());
    }

    @Test
    void doHandle_twoStatements_correctAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[1]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertTrue(answer.getIsCorrect());
        assertEquals(1.0, answer.getScore());
    }

    @Test
    void doHandle_twoStatements_incorrectAnswer() throws Exception {
        // Given
        JsonNode content = mapper.readTree("""
                {"statements":[
                  {"id":1,"text":"Statement 1","compliant":true},
                  {"id":2,"text":"Statement 2","compliant":false}
                ]}
                """);
        JsonNode response = mapper.readTree("{\"selectedStatementIds\":[2]}");
        
        // When
        Answer answer = handler.doHandle(testAttempt, testQuestion, content, response);
        
        // Then
        assertFalse(answer.getIsCorrect());
        assertEquals(0.0, answer.getScore());
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.COMPLIANCE;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}
