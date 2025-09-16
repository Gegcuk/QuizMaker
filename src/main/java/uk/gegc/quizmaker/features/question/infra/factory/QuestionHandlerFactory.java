package uk.gegc.quizmaker.features.question.infra.factory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class QuestionHandlerFactory {
    private final Map<QuestionType, QuestionHandler> handlerMap = new EnumMap<>(QuestionType.class);

    public QuestionHandlerFactory(List<QuestionHandler> handlers) {
        log.info("Initializing QuestionHandlerFactory with {} handlers", handlers.size());
        
        handlers.forEach(handler -> {
            QuestionType supportedType = handler.supportedType();
            handlerMap.put(supportedType, handler);
        });
        
        log.info("QuestionHandlerFactory initialized with handlers for types: {}", handlerMap.keySet());
    }

    public QuestionHandler getHandler(QuestionType type) {
        QuestionHandler questionHandler = handlerMap.get(type);
        if (questionHandler == null) {
            throw new UnsupportedOperationException("No handler for type " + type);
        }
        return questionHandler;
    }
}
