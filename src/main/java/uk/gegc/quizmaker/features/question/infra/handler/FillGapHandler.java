package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class FillGapHandler extends QuestionHandler {

    @Override
    public QuestionType supportedType() {
        return QuestionType.FILL_GAP;
    }

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for FILL_GAP question");
        }

        JsonNode textNode = root.get("text");
        JsonNode gaps = root.get("gaps");

        if (textNode == null || textNode.asText().isBlank()) {
            throw new ValidationException("FILL_GAP requires non-empty 'text' field");
        }

        if (gaps == null || !gaps.isArray() || gaps.isEmpty()) {
            throw new ValidationException("FILL_GAP must have at least one gap defined");
        }

        String text = textNode.asText();
        
        // Collect gap IDs from gaps array
        Set<Integer> gapIds = new HashSet<>();
        for (JsonNode gap : gaps) {
            if (!gap.has("id") || !gap.has("answer") || gap.get("answer").asText().isBlank()) {
                throw new ValidationException("Each gap must have an 'id' and non-empty 'answer'");
            }
            if (!gap.get("id").canConvertToInt()) {
                throw new ValidationException("Gap 'id' must be an Integer");
            }
            int id = gap.get("id").asInt();
            if (id < 1) {
                throw new ValidationException("Gap IDs must be positive integers starting from 1, found: " + id);
            }
            if (gapIds.contains(id)) {
                throw new ValidationException("Gap IDs must be unique, found duplicate ID: " + id);
            }
            gapIds.add(id);
        }
        
        // Extract gap IDs from text using {N} pattern
        Pattern pattern = Pattern.compile("\\{(\\d+)\\}");
        Matcher matcher = pattern.matcher(text);
        Set<Integer> textGapIds = new HashSet<>();
        
        while (matcher.find()) {
            int gapId = Integer.parseInt(matcher.group(1));
            textGapIds.add(gapId);
            
            // Check if this gap ID exists in gaps array
            if (!gapIds.contains(gapId)) {
                throw new ValidationException(
                    "Gap {" + gapId + "} found in text but no corresponding gap with id=" + gapId + " in gaps array"
                );
            }
        }
        
        // Check if all gaps in array are used in text
        for (Integer gapId : gapIds) {
            if (!textGapIds.contains(gapId)) {
                throw new ValidationException(
                    "Gap with id=" + gapId + " defined in gaps array but {" + gapId + "} not found in text"
                );
            }
        }
        
        // Verify we found at least one gap in text
        if (textGapIds.isEmpty()) {
            throw new ValidationException(
                "No gaps found in text. Use {N} format (e.g., {1}, {2}) to mark gap positions"
            );
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

        JsonNode answersNode = response.get("answers");
        Map<Integer, String> given = answersNode != null && answersNode.isArray()
                ? StreamSupport.stream(answersNode.spliterator(), false)
                        .filter(answer -> answer.has("gapId") && answer.has("answer") && 
                                answer.get("gapId").canConvertToInt() && answer.get("answer").isTextual())
                        .collect(Collectors.toMap(
                                answer -> answer.get("gapId").asInt(),
                                answer -> answer.get("answer").asText().trim().toLowerCase()
                        ))
                : Map.of();

        boolean allMatch = correct.entrySet().stream()
                .allMatch(e -> e.getValue().equals(given.get(e.getKey())));

        Answer ans = new Answer();
        ans.setIsCorrect(allMatch);
        ans.setScore(allMatch ? 1.0 : 0.0);
        return ans;
    }
}
