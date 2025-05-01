package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.question.QuestionType;

import static org.junit.jupiter.api.Assertions.*;

public class TrueFalseHandlerTest {

    private TrueFalseHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(){
        handler = new TrueFalseHandler();
        objectMapper = new ObjectMapper();
    }

    private static class FakeRequest implements QuestionContentRequest{

        private final JsonNode content;
        FakeRequest(JsonNode content) { this.content = content; }
        @Override public com.fasterxml.jackson.databind.JsonNode getContent() { return content; }
        @Override public uk.gegc.quizmaker.model.question.QuestionType getType() {
            return uk.gegc.quizmaker.model.question.QuestionType.TRUE_FALSE;
        }
    }

    @Test
    void validTrue_doesNotThrow() throws Exception{
        JsonNode node = objectMapper.readTree("{\"answer\":true}");
        assertDoesNotThrow(() -> handler.validateContent(new FakeRequest(node)));
    }

    @Test
    void validFalse_doesNotThrow() throws Exception{
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

    @Test
    void nullContent_throws() {
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeRequest(null)));
    }



}
