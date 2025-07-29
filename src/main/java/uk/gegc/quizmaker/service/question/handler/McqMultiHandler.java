package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class McqMultiHandler extends QuestionHandler {

    @Override
    public QuestionType supportedType() {
        return QuestionType.MCQ_MULTI;
    }

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for MCQ_MULTI question");
        }

        JsonNode options = root.get("options");
        if (options == null || options.size() < 2 || !options.isArray()) {
            throw new ValidationException("MCQ_MULTI must have at least 2 options");
        }
        boolean hasCorrect = false;
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

        JsonNode selectedNode = response.get("selectedOptionIds");
        Set<String> selected = selectedNode != null && selectedNode.isArray() 
                ? StreamSupport.stream(selectedNode.spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.toSet())
                : Set.of();

        boolean isCorrect = correct.equals(selected);
        Answer answer = new Answer();
        answer.setIsCorrect(isCorrect);
        answer.setScore(isCorrect ? 1.0 : 0.0);
        return answer;
    }
}
