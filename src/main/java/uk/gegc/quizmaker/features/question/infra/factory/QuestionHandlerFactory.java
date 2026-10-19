package uk.gegc.quizmaker.features.question.infra.factory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.shared.exception.ValidationException;

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

    /** Reuse the authoring rules on stored reads without grading or mutating an answer. */
    public JsonNode readValidatedContent(QuestionType type, String raw) {
        JsonNode content = QuestionContracts.read(type, QuestionContracts.View.AUTHOR, raw);
        validateContentRules(type, content);
        return content;
    }

    public void validateStoredContent(QuestionType type, JsonNode content) {
        QuestionContracts.validate(type, QuestionContracts.View.AUTHOR, content);
        validateContentRules(type, content);
    }

    private void validateContentRules(QuestionType type, JsonNode content) {
        try {
            getHandler(type).validateContent(new EntityQuestionContentRequest(type, content));
        } catch (ValidationException ex) {
            // Legacy validators may include author-only answers in their messages.
            throw new QuestionContractException("INVALID_CONTENT", "content");
        }
    }
}
