package uk.gegc.quizmaker.features.question.application.impl;

import uk.gegc.quizmaker.features.question.application.QuestionContracts;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.shared.exception.ValidationException;

@Service
@RequiredArgsConstructor
public class QuestionContentValidationServiceImpl implements QuestionContentValidationService {

    private final QuestionHandlerFactory questionHandlerFactory;

    @Override
    public void validateContent(QuestionType type, JsonNode content) {
        if (type == null) {
            throw new ValidationException("Question type is required");
        }
        var handler = questionHandlerFactory.getHandler(type);
        QuestionContracts.validate(
                type, QuestionContracts.View.AUTHOR, content);
        handler.validateContent(new EntityQuestionContentRequest(type, content));
    }
}
