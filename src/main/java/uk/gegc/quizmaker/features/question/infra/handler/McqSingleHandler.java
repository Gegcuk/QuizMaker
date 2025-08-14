package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.Set;
import java.util.stream.StreamSupport;

@Component
public class McqSingleHandler extends QuestionHandler {
    
    @Override
    public QuestionType supportedType() {
        return QuestionType.MCQ_SINGLE;
    }
    
    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for MCQ_SINGLE question");
        }

        JsonNode options = root.get("options");
        if (options == null || !options.isArray() || options.size() < 2) {
            throw new ValidationException("MCQ_SINGLE must have at least 2 options");
        }

        long correctCount = 0;
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode option : options) {
            // Validate id field
            if (!option.has("id")) {
                throw new ValidationException("Each option must have an 'id' field");
            }
            if (!option.get("id").isTextual() || option.get("id").asText().isBlank()) {
                throw new ValidationException("Option 'id' must be a non-empty string");
            }
            String id = option.get("id").asText();
            if (ids.contains(id)) {
                throw new ValidationException("Option IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);

            // Validate text field
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
