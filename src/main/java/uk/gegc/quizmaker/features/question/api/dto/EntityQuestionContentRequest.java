package uk.gegc.quizmaker.features.question.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

public record EntityQuestionContentRequest(
        QuestionType type,
        JsonNode content
) implements QuestionContentRequest {

    @Override
    public QuestionType getType() {
        return type;
    }

    @Override
    public JsonNode getContent() {
        return content;
    }
}
