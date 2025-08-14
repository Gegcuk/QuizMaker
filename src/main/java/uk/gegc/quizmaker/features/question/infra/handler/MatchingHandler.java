package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

@Component
public class MatchingHandler extends QuestionHandler {

    @Override
    public QuestionType supportedType() {
        return QuestionType.MATCHING;
    }

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for MATCHING question");
        }

        JsonNode left = root.get("left");
        JsonNode right = root.get("right");

        if (left == null || !left.isArray() || left.size() < 2) {
            throw new ValidationException("MATCHING must have at least 2 left items");
        }
        if (right == null || !right.isArray() || right.size() < 2) {
            throw new ValidationException("MATCHING must have at least 2 right items");
        }

        // Validate left items: id(int), text(non-empty), matchId(int)
        Set<Integer> leftIds = new HashSet<>();
        Set<Integer> rightIdsReferenced = new HashSet<>();
        for (JsonNode l : left) {
            if (!l.has("id") || !l.get("id").canConvertToInt()) {
                throw new ValidationException("Each left item must have integer 'id'");
            }
            int id = l.get("id").asInt();
            if (!leftIds.add(id)) {
                throw new ValidationException("Left item IDs must be unique, duplicate: " + id);
            }
            if (!l.has("text") || l.get("text").asText().isBlank()) {
                throw new ValidationException("Each left item needs non-empty 'text'");
            }
            if (!l.has("matchId") || !l.get("matchId").canConvertToInt()) {
                throw new ValidationException("Each left item must have integer 'matchId'");
            }
            rightIdsReferenced.add(l.get("matchId").asInt());
        }

        // Validate right items: id(int), text(non-empty)
        Set<Integer> rightIds = new HashSet<>();
        for (JsonNode r : right) {
            if (!r.has("id") || !r.get("id").canConvertToInt()) {
                throw new ValidationException("Each right item must have integer 'id'");
            }
            int id = r.get("id").asInt();
            if (!rightIds.add(id)) {
                throw new ValidationException("Right item IDs must be unique, duplicate: " + id);
            }
            if (!r.has("text") || r.get("text").asText().isBlank()) {
                throw new ValidationException("Each right item needs non-empty 'text'");
            }
        }

        // Ensure all referenced right IDs exist
        if (!rightIds.containsAll(rightIdsReferenced)) {
            throw new ValidationException("Some left.matchId values do not correspond to right item IDs");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt, Question question, JsonNode content, JsonNode response) {
        // Build map of correct pairs: leftId -> rightId
        Map<Integer, Integer> correctPairs = new HashMap<>();
        StreamSupport.stream(content.get("left").spliterator(), false)
                .forEach(l -> correctPairs.put(l.get("id").asInt(), l.get("matchId").asInt()));

        // Parse user matches: array of {"leftId":int, "rightId":int}
        Map<Integer, Integer> userPairs = new HashMap<>();
        JsonNode matches = response.get("matches");
        if (matches != null && matches.isArray()) {
            matches.forEach(m -> {
                if (m.has("leftId") && m.has("rightId") &&
                        m.get("leftId").canConvertToInt() && m.get("rightId").canConvertToInt()) {
                    userPairs.put(m.get("leftId").asInt(), m.get("rightId").asInt());
                }
            });
        }

        boolean allCorrect = correctPairs.entrySet().stream()
                .allMatch(e -> e.getValue().equals(userPairs.get(e.getKey())));

        Answer ans = new Answer();
        ans.setIsCorrect(allCorrect);
        ans.setScore(allCorrect ? 1.0 : 0.0);
        return ans;
    }
}


