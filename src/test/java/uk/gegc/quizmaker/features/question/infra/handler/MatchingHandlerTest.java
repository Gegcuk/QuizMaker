package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
class MatchingHandlerTest {
    private MatchingHandler handler;
    private ObjectMapper mapper;
    private Attempt attempt;
    private Question question;

    @BeforeEach
    void setUp() {
        handler = new MatchingHandler();
        mapper = new ObjectMapper();
        attempt = new Attempt();
        attempt.setId(UUID.randomUUID());
        attempt.setStartedAt(Instant.now());
        question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.MATCHING);
    }

    @Test
    void validate_validPayload_ok() throws Exception {
        JsonNode content = mapper.readTree("""
                {
                  "left": [
                    {"id":1,"text":"Apple","matchId":10},
                    {"id":2,"text":"Banana","matchId":11}
                  ],
                  "right": [
                    {"id":10,"text":"Red"},
                    {"id":11,"text":"Yellow"}
                  ]
                }
                """);
        assertDoesNotThrow(() -> handler.validateContent(new FakeReq(content)));
    }

    @Test
    void validate_missingRightReference_throws() throws Exception {
        JsonNode content = mapper.readTree("""
                {
                  "left": [
                    {"id":1,"text":"Apple","matchId":999}
                  ],
                  "right": [
                    {"id":10,"text":"Red"}
                  ]
                }
                """);
        assertThrows(ValidationException.class, () -> handler.validateContent(new FakeReq(content)));
    }

    @Test
    void doHandle_allCorrect_returnsCorrect() throws Exception {
        JsonNode content = mapper.readTree("""
                {
                  "left": [
                    {"id":1,"text":"A","matchId":10},
                    {"id":2,"text":"B","matchId":11}
                  ],
                  "right": [
                    {"id":10,"text":"X"},
                    {"id":11,"text":"Y"}
                  ]
                }
                """);
        JsonNode response = mapper.readTree("""
                {"matches":[{"leftId":1,"rightId":10},{"leftId":2,"rightId":11}]}
                """);
        Answer ans = handler.doHandle(attempt, question, content, response);
        assertTrue(ans.getIsCorrect());
        assertEquals(1.0, ans.getScore());
    }

    @Test
    void doHandle_partialOrWrong_returnsIncorrect() throws Exception {
        JsonNode content = mapper.readTree("""
                {
                  "left": [
                    {"id":1,"text":"A","matchId":10},
                    {"id":2,"text":"B","matchId":11}
                  ],
                  "right": [
                    {"id":10,"text":"X"},
                    {"id":11,"text":"Y"}
                  ]
                }
                """);
        JsonNode response = mapper.readTree("""
                {"matches":[{"leftId":1,"rightId":11}]}
                """);
        Answer ans = handler.doHandle(attempt, question, content, response);
        assertFalse(ans.getIsCorrect());
        assertEquals(0.0, ans.getScore());
    }

    record FakeReq(JsonNode content) implements QuestionContentRequest {
        @Override
        public QuestionType getType() { return QuestionType.MATCHING; }
        @Override
        public JsonNode getContent() { return content; }
    }
}


