package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;

@Component
public class TrueFalseHandler extends QuestionHandler {

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for TRUE_FALSE question");
        }

        JsonNode answer = root.get("answer");
        if (answer == null || !answer.isBoolean()) {
            throw new ValidationException("TRUE_FALSE requires an 'answer' boolean field");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt, Question question, JsonNode content, JsonNode response) {
        boolean correctAnswer = content.get("answer").asBoolean();
        JsonNode userAnswerNode = response.get("answer");
        boolean userAnswer = userAnswerNode != null && userAnswerNode.isBoolean() ? userAnswerNode.asBoolean() : false;
        boolean isCorrect = userAnswer == correctAnswer;
        Answer answer = new Answer();
        answer.setIsCorrect(isCorrect);
        answer.setScore(isCorrect ? 1.0 : 0.0);
        return answer;
    }
}
