package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.question.QuestionType;

import static org.junit.jupiter.api.Assertions.*;

class McqSingleHandlerTest {
    private McqSingleHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new McqSingleHandler();
        mapper  = new ObjectMapper();
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override public QuestionType getType()    { return QuestionType.MCQ_SINGLE; }
        @Override public JsonNode     getContent() { return content;               }
    }

    @Test void validTwoOptions_exactlyOneCorrect() throws Exception {
        JsonNode payload = mapper.readTree("""
          {"options":[
            {"text":"A","correct":false},
            {"text":"B","correct":true}
          ]}
        """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(payload)));
    }

    @Test void missingOptions_throws() {
        JsonNode p = mapper.createObjectNode();
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void optionsNotArray_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"options":"nope"}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void tooFewOptions_throws() throws Exception {
        JsonNode p = mapper.readTree("""
          {"options":[{"text":"A","correct":true}]}
        """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void noCorrect_throws() throws Exception {
        JsonNode p = mapper.readTree("""
          {"options":[
            {"text":"A","correct":false},
            {"text":"B","correct":false}
          ]}
        """);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
        assertTrue(ex.getMessage().contains("exactly one correct"));
    }

    @Test void moreThanOneCorrect_throws() throws Exception {
        JsonNode p = mapper.readTree("""
          {"options":[
            {"text":"A","correct":true},
            {"text":"B","correct":true},
            {"text":"C","correct":false}
          ]}
        """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void missingText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
          {"options":[
            {"correct":true},
            {"text":"B","correct":false}
          ]}
        """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void blankText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
          {"options":[
            {"text":"","correct":true},
            {"text":"B","correct":false}
          ]}
        """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test void nullContent_throws() {
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(null)));
    }
}