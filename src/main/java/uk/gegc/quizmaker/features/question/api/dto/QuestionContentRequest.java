package uk.gegc.quizmaker.features.question.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

public interface QuestionContentRequest {
    QuestionType getType();

    JsonNode getContent();
}
