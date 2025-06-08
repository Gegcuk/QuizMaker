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
class HotspotHandlerTest {
    private HotspotHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new HotspotHandler();
        mapper = new ObjectMapper();
    }

    @Test
    void validSingleRegion_doesNotThrow() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"imageUrl":"http://x","regions":[{"x":1,"y":2,"width":3,"height":4}]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingImageUrl_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"regions":[{"x":1,"y":2,"width":3,"height":4}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankImageUrl_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"imageUrl":"","regions":[{"x":1,"y":2,"width":3,"height":4}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingRegions_throws() {
        JsonNode p = mapper.createObjectNode().put("imageUrl", "http://x");
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
    void regionMissingField_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"imageUrl":"http://x","regions":[{"y":2,"width":3,"height":4}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void regionNonIntField_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"imageUrl":"http://x","regions":[{"x":"bad","y":2,"width":3,"height":4}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
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