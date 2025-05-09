package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

@Component
public class HotspotHandler extends QuestionHandler {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void validateContent(QuestionContentRequest request) {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for ORDERING question");
        }

        JsonNode imageUrl = root.get("imageUrl");
        JsonNode regions = root.get("regions");

        if (imageUrl == null || imageUrl.asText().isBlank()) {
            throw new ValidationException("HOTSPOT requires a non-empty 'imageUrl'");
        }

        if (regions == null || !regions.isArray() || regions.isEmpty()) {
            throw new ValidationException("HOTSPOT must have at least one region");
        }

        for (JsonNode region : regions) {
            for (String field : new String[]{"x", "y", "width", "height"}) {
                if (!region.has(field) || !region.get(field).canConvertToInt()) {
                    throw new ValidationException("Each region must have integer '" + field + "'");
                }
            }
        }
    }
}
