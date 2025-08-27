package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

@Component
public class OrderingHandler extends QuestionHandler {

    @Override
    public QuestionType supportedType() {
        return QuestionType.ORDERING;
    }

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
        
        if (items.size() > 10) {
            throw new ValidationException("ORDERING must have at most 10 items");
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

        JsonNode itemIdsNode = response.get("orderedItemIds");
        List<Integer> userOrder = itemIdsNode != null && itemIdsNode.isArray()
                ? StreamSupport.stream(itemIdsNode.spliterator(), false)
                        .filter(id -> id.canConvertToInt())
                        .map(JsonNode::asInt)
                        .toList()
                : List.of();

        boolean isCorrect = correctOrder.equals(userOrder);
        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
