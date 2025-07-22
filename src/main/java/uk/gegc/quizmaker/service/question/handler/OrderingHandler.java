package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;

import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;


@Component
public class OrderingHandler extends QuestionHandler {

    @Override
    public void validateContent(QuestionContentRequest req) {
        JsonNode root = req.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for ORDERING question");
        }

        JsonNode items = root.get("items");
        if (items == null || !items.isArray() || items.size() < 2) {
            throw new ValidationException("ORDERING must have at least 2 items");
        }

        Set<Integer> ids = new java.util.HashSet<>();
        for (JsonNode it : items) {
            if (!it.has("id") || !it.has("text") || it.get("text").asText().isBlank()) {
                throw new ValidationException("Each item needs an 'id' and non-empty 'text'");
            }
            if (!it.get("id").canConvertToInt()) {
                throw new ValidationException("Item 'id' must be an integer");
            }
            int id = it.get("id").asInt();
            if (ids.contains(id)) {
                throw new ValidationException("Item IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt,
                              Question question,
                              JsonNode content,
                              JsonNode response) {
        List<Integer> correctOrder = StreamSupport.stream(content.get("items").spliterator(), false)
                .map(item -> item.get("id").asInt())
                .toList();

        List<Integer> userOrder = StreamSupport.stream(response.get("itemIds").spliterator(), false)
                .map(JsonNode::asInt)
                .toList();

        boolean isCorrect = correctOrder.equals(userOrder);
        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
