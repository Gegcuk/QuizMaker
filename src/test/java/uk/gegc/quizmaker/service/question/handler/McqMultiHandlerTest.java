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
class McqMultiHandlerTest {
    private McqMultiHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new McqMultiHandler();
        mapper = new ObjectMapper();
    }

    @Test
    void validTwoOptions_oneCorrect() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"text":"A","correct":true},
                    {"text":"B","correct":false}
                  ]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void validMultipleCorrect() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"text":"A","correct":true},
                    {"text":"B","correct":true},
                    {"text":"C","correct":false}
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
                  {"options":[{"text":"A","correct":false}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void noCorrect_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"text":"A","correct":false},
                    {"text":"B","correct":false}
                  ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"correct":true},
                    {"text":"B","correct":false}
                  ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"options":[
                    {"text":" ","correct":true},
                    {"text":"B","correct":false}
                  ]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
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