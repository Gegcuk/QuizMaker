package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

@Component
public class TrueFalseHandler extends QuestionHandler {

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for TRUE_FALSE question");
        }

        JsonNode answer = root.get("answer");
        if (answer == null || !answer.isBoolean()) {
            throw new ValidationException("TRUE_FALSE requires an 'answer' boolean field");
        }
    }
}
