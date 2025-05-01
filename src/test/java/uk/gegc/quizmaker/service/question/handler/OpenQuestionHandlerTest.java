package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.question.QuestionType;

import static org.junit.jupiter.api.Assertions.*;

class OpenQuestionHandlerTest {
    private OpenQuestionHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new OpenQuestionHandler();
        mapper  = new ObjectMapper();
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override public QuestionType getType()    { return QuestionType.OPEN; }
        @Override public JsonNode     getContent() { return content;            }
    }

    @Test void validAnswer_doesNotThrow() throws Exception {
        JsonNode p = mapper.readTree("""
                {"answer":"some answer"}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test void missingAnswer_throws() {
        JsonNode p = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void blankAnswer_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"answer":""}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void nullContent_throws() {
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(null)));
    }
}