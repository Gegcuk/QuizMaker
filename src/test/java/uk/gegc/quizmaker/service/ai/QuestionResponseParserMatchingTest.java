package uk.gegc.quizmaker.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParserImpl;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionParserFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class QuestionResponseParserMatchingTest {

    @Mock
    private QuestionParserFactory questionParserFactory;

    @Mock
    private QuestionHandlerFactory handlerFactory;

    @Mock
    private QuestionHandler handler;

    @InjectMocks
    private QuestionResponseParserImpl parser;

    @BeforeEach
    void setup() throws Exception {
        lenient().when(handlerFactory.getHandler(any())).thenReturn(handler);
        // allow validation to pass
        lenient().doNothing().when(handler).validateContent(any());
    }

    @Test
    void parsesMatchingQuestions_viaGenericParser() throws Exception {
        String ai = """
                {"questions":[{"type":"MATCHING","questionText":"Match fruits to colors","difficulty":"EASY","content":{
                  "left":[{"id":1,"text":"Apple","matchId":10}],
                  "right":[{"id":10,"text":"Red"}]}}]}
                """;

        Question q = new Question();
        q.setType(QuestionType.MATCHING);
        q.setQuestionText("Match fruits to colors");
        q.setContent("{\"left\":[{\"id\":1,\"text\":\"Apple\",\"matchId\":10}],\"right\":[{\"id\":10,\"text\":\"Red\"}]}");

        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MATCHING)))
                .thenReturn(List.of(q));

        List<Question> result = parser.parseQuestionsFromAIResponse(ai, QuestionType.MATCHING);
        assertEquals(1, result.size());
        assertEquals(QuestionType.MATCHING, result.get(0).getType());
        assertEquals("Match fruits to colors", result.get(0).getQuestionText());
    }

    @Test
    void invalidMatchingPayload_throws() {
        String ai = """
                {"questions":[{"type":"MATCHING","questionText":"","content":{}}]}
                """;

        when(questionParserFactory.parseQuestions(any(), eq(QuestionType.MATCHING)))
                .thenThrow(new AIResponseParseException("No 'questions' array found in content"));

        assertThrows(AIResponseParseException.class, () -> parser.parseQuestionsFromAIResponse(ai, QuestionType.MATCHING));
    }
}


