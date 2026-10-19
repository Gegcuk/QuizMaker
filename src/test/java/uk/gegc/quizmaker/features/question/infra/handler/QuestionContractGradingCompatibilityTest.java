package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class QuestionContractGradingCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(QuestionType.class)
    void validCorrectAndWrongResponsesKeepExistingGradesAndMalformedDataNeverAddsAnAnswer(QuestionType type) {
        QuestionHandler handler = switch (type) {
            case MCQ_SINGLE -> new McqSingleHandler(); case MCQ_MULTI -> new McqMultiHandler();
            case TRUE_FALSE -> new TrueFalseHandler(); case OPEN -> new OpenQuestionHandler();
            case FILL_GAP -> new FillGapHandler(); case ORDERING -> new OrderingHandler();
            case HOTSPOT -> new HotspotHandler(); case MATCHING -> new MatchingHandler();
            case COMPLIANCE -> new ComplianceHandler();
        };
        Question question = new Question(); question.setId(UUID.randomUUID()); question.setType(type);
        question.setContent(QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString());
        var good = QuestionContracts.example(type, QuestionContracts.View.RESPONSE);
        var attempt = new Attempt();
        var answer = handler.handle(attempt, question, new AnswerSubmissionRequest(question.getId(), good, false, false, false));
        assertThat(answer.getIsCorrect()).isTrue(); assertThat(answer.getScore()).isEqualTo(1.0);

        var wrong = json.createObjectNode();
        switch (type) {
            case MCQ_SINGLE -> wrong.put("selectedOptionId", "b");
            case MCQ_MULTI -> wrong.putArray("selectedOptionIds").add("c");
            case TRUE_FALSE -> wrong.put("answer", false);
            case OPEN -> wrong.put("answer", "London");
            case FILL_GAP -> wrong.putArray("answers").addObject().put("gapId", 1).put("answer", "London");
            case ORDERING -> wrong.putArray("orderedItemIds").add(2).add(1);
            case HOTSPOT -> wrong.put("selectedRegionId", 1);
            case MATCHING -> wrong.putArray("matches").addObject().put("leftId", 1).put("rightId", 11);
            case COMPLIANCE -> wrong.putArray("selectedStatementIds").add(2);
        }
        var incorrect = handler.handle(attempt, question, new AnswerSubmissionRequest(question.getId(), wrong, false, false, false));
        assertThat(incorrect.getIsCorrect()).isFalse(); assertThat(incorrect.getScore()).isZero();
        assertThatThrownBy(() -> handler.handle(attempt, question,
                new AnswerSubmissionRequest(question.getId(), json.createObjectNode(), false, false, false)))
                .isInstanceOf(QuestionContractException.class);
        assertThat(attempt.getAnswers()).hasSize(2);
    }
}
