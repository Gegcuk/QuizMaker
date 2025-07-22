package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.question.QuestionType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Execution(ExecutionMode.CONCURRENT)
class ComplianceHandlerTest {
    private ComplianceHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new ComplianceHandler();
        mapper = new ObjectMapper();
    }

    @Test
    void validOneStatement_doesNotThrow() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"statements":[{"id":1,"text":"T","compliant":true}]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
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
    void missingTextInStatement_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"statements":[{"id":1,"compliant":true}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankTextInStatement_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"statements":[{"id":1,"text":" ","compliant":true}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void noneCompliant_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"statements":[{"id":1,"text":"T","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingIdInStatement_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"statements":[{"text":"T","compliant":true}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void duplicateIds_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"statements":[{"id":1,"text":"T","compliant":true},{"id":1,"text":"F","compliant":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
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