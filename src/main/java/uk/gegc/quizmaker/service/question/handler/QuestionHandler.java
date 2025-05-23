package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.attempt.AnswerSubmissionRequest;
import uk.gegc.quizmaker.dto.question.EntityQuestionContentRequest;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;

import java.time.Instant;

public abstract class QuestionHandler {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public abstract void validateContent(QuestionContentRequest request) throws ValidationException;

    public Answer handle(Attempt attempt, Question question, AnswerSubmissionRequest request) {
        JsonNode content;
        try {
            content = objectMapper.readTree(question.getContent());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Malformed question JSON for question " + question.getId(),
                    e
            );
        }

        var qc = new EntityQuestionContentRequest(question.getType(), content);
        validateContent(qc);

        Answer answer = doHandle(attempt, question, content, request.response());
        answer.setAttempt(attempt);
        answer.setQuestion(question);
        answer.setResponse(request.response().toString());
        answer.setAnsweredAt(Instant.now());
        attempt.getAnswers().add(answer);
        return answer;
    }

    protected abstract Answer doHandle(
            Attempt attempt,
            Question question,
            JsonNode content,
            JsonNode response
    );
}
