package uk.gegc.quizmaker.dto.question;

import uk.gegc.quizmaker.model.question.QuestionType;

public interface QuestionContentRequest {
    QuestionType getType();
    String getContent();
}
