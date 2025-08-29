package uk.gegc.quizmaker.features.question.infra.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.Instant;

public abstract class QuestionHandler {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns the question type that this handler supports
     * @return the supported question type
     */
    public abstract QuestionType supportedType();

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
