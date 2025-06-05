package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class FillGapHandler extends QuestionHandler {

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for ORDERING question");
        }

        JsonNode text = root.get("text");
        JsonNode gaps = root.get("gaps");

        if (text == null || text.asText().isBlank()) {
            throw new ValidationException("FILL_GAP requires non-empty 'text' field");
        }

        if (gaps == null || !gaps.isArray() || gaps.isEmpty()) {
            throw new ValidationException("FILL_GAP must have at least one gap defined");
        }

        for (JsonNode gap : gaps) {
            if (!gap.has("id") || !gap.has("answer") || gap.get("answer").asText().isBlank()) {
                throw new ValidationException("Each gap must have an 'id' and non-empty 'answer'");
            }
            if (!gap.get("id").canConvertToInt()) {
                throw new ValidationException("Gap 'id' must be an Integer");
            }
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt,
                              Question question,
                              JsonNode content,
                              JsonNode response) {
        Map<Integer, String> correct = StreamSupport.stream(content.get("gaps").spliterator(), false)
                .collect(Collectors.toMap(
                        gap -> gap.get("id").asInt(),
                        gap -> gap.get("answer").asText().trim().toLowerCase()
                ));

        Map<Integer, String> given = StreamSupport.stream(response.get("gaps").spliterator(), false)
                .collect(Collectors.toMap(
                        gap -> gap.get("id").asInt(),
                        gap -> gap.get("answer").asText().trim().toLowerCase()
                ));

        boolean allMatch = correct.entrySet().stream()
                .allMatch(e -> e.getValue().equals(given.get(e.getKey())));

        Answer ans = new Answer();
        ans.setIsCorrect(allMatch);
        ans.setScore(allMatch ? 1.0 : 0.0);
        return ans;
    }
}
