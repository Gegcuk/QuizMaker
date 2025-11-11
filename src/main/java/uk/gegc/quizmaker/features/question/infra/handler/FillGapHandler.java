package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.features.question.application.FillGapContentValidator;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.Map;
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
        FillGapContentValidator.ValidationResult result = 
            FillGapContentValidator.validate(request.getContent());
        
        if (!result.valid()) {
            throw new ValidationException(result.errorMessage());
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
