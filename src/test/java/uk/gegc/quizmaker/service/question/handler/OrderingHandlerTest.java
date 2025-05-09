package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderingHandlerTest {

    private OrderingHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new OrderingHandler();
        mapper = new ObjectMapper();
    }

    @Test
    void validItems_doesNotThrow() throws Exception {
        JsonNode node = mapper.readTree("""
                    {"items":[{"id":1,"text":"A"},{"id":2,"text":"B"}]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(node)));
    }

    @Test
    void missingItems_throws() throws Exception {
        JsonNode node = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(node)));
    }

    @Test
    void tooFewItems_throws() throws Exception {
        JsonNode node = mapper.readTree("""
                    {"items":[{"id":1,"text":"A"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(node)));
    }

    @Test
    void nonIntId_throws() throws Exception {
        JsonNode node = mapper.readTree("""
                    {"items":[{"id":"one","text":"A"},{"id":2,"text":"B"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(node)));
    }

    private static class FakeReq implements QuestionContentRequest {
        private final JsonNode content;

        FakeReq(JsonNode content) {
            this.content = content;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }

        @Override
        public uk.gegc.quizmaker.model.question.QuestionType getType() {
            return uk.gegc.quizmaker.model.question.QuestionType.ORDERING;
        }
    }
}