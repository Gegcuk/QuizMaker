package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class HotspotHandler extends QuestionHandler {

    @Override
    public QuestionType supportedType() {
        return QuestionType.HOTSPOT;
    }

    @Override
    public void validateContent(QuestionContentRequest request) {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for HOTSPOT question");
        }

        JsonNode imageUrl = root.get("imageUrl");
        JsonNode regions = root.get("regions");

        if (imageUrl == null || imageUrl.asText().isBlank()) {
            throw new ValidationException("HOTSPOT requires a non-empty 'imageUrl'");
        }

        if (regions == null || !regions.isArray() || regions.size() < 2) {
            throw new ValidationException("HOTSPOT must have at least 2 regions");
        }

        if (regions.size() > 6) {
            throw new ValidationException("HOTSPOT must have at most 6 regions");
        }

        Set<Integer> ids = new java.util.HashSet<>();
        boolean hasCorrectRegion = false;
        for (JsonNode region : regions) {
            // Validate id field
            if (!region.has("id")) {
                throw new ValidationException("Each region must have an 'id' field");
            }
            if (!region.get("id").canConvertToInt()) {
                throw new ValidationException("Region 'id' must be an integer");
            }
            int id = region.get("id").asInt();
            if (ids.contains(id)) {
                throw new ValidationException("Region IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);
            
            // Validate correct field
            if (!region.has("correct")) {
                throw new ValidationException("Each region must have a 'correct' field");
            }
            if (!region.get("correct").isBoolean()) {
                throw new ValidationException("Region 'correct' field must be a boolean");
            }
            if (region.get("correct").asBoolean()) {
                hasCorrectRegion = true;
            }
            
            // Validate coordinate fields
            for (String field : new String[]{"x", "y", "width", "height"}) {
                if (!region.has(field) || !region.get(field).canConvertToInt()) {
                    throw new ValidationException("Each region must have integer '" + field + "'");
                }
                int value = region.get(field).asInt();
                if (value < 0) {
                    throw new ValidationException("Region '" + field + "' must be non-negative");
                }
            }
        }
        
        if (!hasCorrectRegion) {
            throw new ValidationException("At least one region must be marked as correct");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt,
                              Question question,
                              JsonNode content,
                              JsonNode response) {
        Set<Integer> correctIds = StreamSupport.stream(
                        content.get("regions").spliterator(), false)
                .filter(r -> r.path("correct").asBoolean(false))
                .map(r -> r.get("id").asInt())
                .collect(Collectors.toSet());

        JsonNode selectedNode = response.get("selectedRegionId");
        int selected = selectedNode != null && selectedNode.canConvertToInt() ? selectedNode.asInt() : -1;
        boolean isCorrect = correctIds.contains(selected);

        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
