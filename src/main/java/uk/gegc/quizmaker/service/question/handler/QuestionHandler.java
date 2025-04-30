package uk.gegc.quizmaker.service.question.handler;

import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.exception.ValidationException;

public abstract class QuestionHandler {
    public abstract void validateContent(CreateQuestionRequest request) throws ValidationException;
}
