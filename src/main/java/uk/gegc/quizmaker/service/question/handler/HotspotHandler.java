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
public class HotspotHandler extends QuestionHandler {

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

        int selected = response.get("regionId").asInt(-1);
        boolean isCorrect = correctIds.contains(selected);

        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
