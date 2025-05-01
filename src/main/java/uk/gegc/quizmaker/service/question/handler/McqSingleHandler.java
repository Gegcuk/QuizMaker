package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

import java.io.IOException;

@Component
public class McqSingleHandler extends QuestionHandler{
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for MCQ_SINGLE");
        }

        JsonNode options = root.get("options");
        if (options == null || !options.isArray() || options.size() < 2) {
            throw new ValidationException("MCQ_SINGLE must have at least 2 options");
        }

        long correctCount = 0;
        for (JsonNode option : options) {
            JsonNode textNode = option.get("text");
            if (textNode == null || textNode.asText().isBlank()) {
                throw new ValidationException("Each option needs a non-empty 'text'");
            }
            if (option.path("correct").asBoolean(false)) {
                correctCount++;
            }
        }

        if (correctCount != 1) {
            throw new ValidationException("MCQ_SINGLE must have exactly one correct answer");
        }
    }
}
