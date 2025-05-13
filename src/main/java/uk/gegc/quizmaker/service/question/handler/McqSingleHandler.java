package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;

import java.util.stream.StreamSupport;

@Component
public class McqSingleHandler extends QuestionHandler {
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

    @Override
    protected Answer doHandle(Attempt attempt, Question question, JsonNode content, JsonNode response) {
        String correctId = StreamSupport.stream(content.get("options").spliterator(), false)
                .filter(option -> option.path("correct").asBoolean())
                .map(option -> option.get("id").asText())
                .findFirst()
                .orElse("");

        String selected = response.path("selectedOptionId").asText("");

        boolean isCorrect = selected.equals(correctId);
        Answer answer = new Answer();
        answer.setIsCorrect(isCorrect);
        answer.setScore(isCorrect ? 1.0 : 0.0);

        return answer;
    }
}
