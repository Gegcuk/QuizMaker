package uk.gegc.quizmaker.service.question.handler;

import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

public abstract class QuestionHandler {
    public abstract void validateContent(QuestionContentRequest request) throws ValidationException;
}
