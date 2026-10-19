package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;

import java.util.List;

public final class QuestionContractTestSupport {
    private QuestionContractTestSupport() { }

    public static QuestionHandlerFactory handlerFactory() {
        return new QuestionHandlerFactory(List.of(new McqSingleHandler(), new McqMultiHandler(),
                new TrueFalseHandler(), new OpenQuestionHandler(), new FillGapHandler(),
                new OrderingHandler(), new MatchingHandler(), new HotspotHandler(), new ComplianceHandler()));
    }

    public static SafeQuestionContentBuilder safeContentBuilder() {
        return new SafeQuestionContentBuilder(handlerFactory());
    }

    public static JsonNode structurallyValidButUnanswerable(QuestionType type) {
        var content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
        switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> content.path("options").forEach(option -> ((ObjectNode) option).put("correct", false));
            case OPEN -> content.put("answer", "   ");
            case FILL_GAP -> content.put("text", "Capital: {99999999999999999999}");
            case HOTSPOT -> content.path("regions").forEach(region -> ((ObjectNode) region).put("correct", false));
            case COMPLIANCE -> content.path("statements").forEach(statement -> ((ObjectNode) statement).put("compliant", false));
            case MATCHING -> ((ObjectNode) content.path("left").get(0)).put("matchId", 999);
            default -> throw new IllegalArgumentException("No semantic fixture for " + type);
        }
        return content;
    }
}
