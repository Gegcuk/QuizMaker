package uk.gegc.quizmaker.dto.question;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.model.question.QuestionType;

public interface QuestionContentRequest {
    QuestionType getType();

    JsonNode getContent();
}
