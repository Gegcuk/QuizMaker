package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class McqMultiHandler extends QuestionHandler {
    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for ORDERING question");
        }

        JsonNode options = root.get("options");
        if (options == null || options.size() < 2 || !options.isArray()) {
            throw new ValidationException("MCQ_MULTI must have at least 2 options");
        }
        boolean hasCorrect = false;
        for (JsonNode option : options) {
            if (!option.has("text") || option.get("text").asText().isBlank()) {
                throw new ValidationException("Each option needs a non-empty 'text'");
            }
            if (option.path("correct").asBoolean(false)) {
                hasCorrect = true;
            }
        }
        if (!hasCorrect)
            throw new ValidationException("MCQ_MULTI must have at least one correct answer");
    }

    @Override
    protected Answer doHandle(Attempt attempt, Question question, JsonNode content, JsonNode response) {
        Set<String> correct = StreamSupport.stream(content.get("options").spliterator(), false)
                .filter(option -> option.path("correct").asBoolean(false))
                .map(option -> option.get("id").asText())
                .collect(Collectors.toSet());

        Set<String> selected = StreamSupport.stream(response.get("selectedOptionIds").spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());

        boolean isCorrect = correct.equals(selected);
        Answer answer = new Answer();
        answer.setIsCorrect(isCorrect);
        answer.setScore(isCorrect ? 1.0 : 0.0);
        return answer;
    }
}
