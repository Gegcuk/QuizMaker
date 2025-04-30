package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;


@Component
public class OrderingHandler extends QuestionHandler {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void validateContent(QuestionContentRequest req) {
        JsonNode root;
        try {
            root = objectMapper.readTree(req.getContent());
        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON for ORDERING question");
        }
        JsonNode items = root.get("items");
        if (items == null || !items.isArray() || items.size() < 2) {
            throw new ValidationException("ORDERING must have at least 2 items");
        }
        for (JsonNode it : items) {
            if (!it.has("id") || !it.has("text") || it.get("text").asText().isBlank()) {
                throw new ValidationException("Each item needs an 'id' and non-empty 'text'");
            }
            if (!it.get("id").canConvertToInt()) {
                throw new ValidationException("Item 'id' must be an integer");
            }
        }
    }
}
