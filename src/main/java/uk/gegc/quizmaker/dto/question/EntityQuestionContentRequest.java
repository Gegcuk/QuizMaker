package uk.gegc.quizmaker.dto.question;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.model.question.QuestionType;

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
