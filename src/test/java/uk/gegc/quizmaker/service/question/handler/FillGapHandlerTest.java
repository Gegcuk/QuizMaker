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
class FillGapHandlerTest {
    private FillGapHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        handler = new FillGapHandler();
        mapper = new ObjectMapper();
    }

    @Test
    void validSingleGap_doesNotThrow() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"text":"Fill here","gaps":[{"id":1,"answer":"X"}]}
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"gaps":[{"id":1,"answer":"X"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void blankText_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"","gaps":[{"id":1,"answer":"X"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void missingGaps_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"OK"}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void emptyGaps_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                {"text":"OK","gaps":[]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void gapMissingId_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"text":"OK","gaps":[{"answer":"X"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void gapNonIntId_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"text":"OK","gaps":[{"id":"one","answer":"X"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void gapMissingAnswer_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"text":"OK","gaps":[{"id":1}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void gapBlankAnswer_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"text":"OK","gaps":[{"id":1,"answer":""}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    @Test
    void duplicateIds_throws() throws Exception {
        JsonNode p = mapper.readTree("""
                  {"text":"OK","gaps":[{"id":1,"answer":"X"},{"id":1,"answer":"Y"}]}
                """);
        assertThrows(ValidationException.class,
                () -> handler.validateContent(new FakeReq(p)));
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() {
            return QuestionType.FILL_GAP;
        }

        @Override
        public JsonNode getContent() {
            return content;
        }
    }
}