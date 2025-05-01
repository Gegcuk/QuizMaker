package uk.gegc.quizmaker.service.question.factory;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.question.handler.*;

import java.util.EnumMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class QuestionHandlerFactory {
    private final Map<QuestionType, QuestionHandler> handlerMap = new EnumMap<>(QuestionType.class);

    private final McqSingleHandler mcqSingleHandler;
    private final TrueFalseHandler trueFalseHandler;
    private final ComplianceHandler complianceHandler;
    private final FillGapHandler fillGapHandler;
    private final HotspotHandler hotspotHandler;
    private final McqMultiHandler mcqMultiHandler;
    private final OpenQuestionHandler openQuestionHandler;
    private final OrderingHandler orderingHandler;

    @PostConstruct
    private void init(){
        handlerMap.put(QuestionType.MCQ_SINGLE, mcqSingleHandler);
        handlerMap.put(QuestionType.MCQ_MULTI, mcqMultiHandler);
        handlerMap.put(QuestionType.COMPLIANCE, complianceHandler);
        handlerMap.put(QuestionType.TRUE_FALSE, trueFalseHandler);
        handlerMap.put(QuestionType.FILL_GAP, fillGapHandler);
        handlerMap.put(QuestionType.HOTSPOT, hotspotHandler);
        handlerMap.put(QuestionType.OPEN, openQuestionHandler);
        handlerMap.put(QuestionType.ORDERING, orderingHandler);
    }

    public QuestionHandler getHandler(QuestionType type){
        QuestionHandler questionHandler = handlerMap.get(type);
        if(questionHandler == null) {
            throw new UnsupportedOperationException("No handler for type " + type);
        }
        return questionHandler;
    }
}
