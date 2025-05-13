package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;

@Component
public class OpenQuestionHandler extends QuestionHandler {

    @Override
    public void validateContent(QuestionContentRequest request) {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for ORDERING question");
        }

        JsonNode answer = root.get("answer");
        if (answer == null || answer.asText().isBlank()) {
            throw new ValidationException("OPEN question must have a non-empty 'answer' field");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt,
                              Question question,
                              JsonNode content,
                              JsonNode response) {
        String correct = content.get("answer").asText().trim().toLowerCase();
        String given   = response.get("answer").asText().trim().toLowerCase();
        boolean isCorrect = correct.equals(given);

        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
