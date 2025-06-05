package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
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
public class ComplianceHandler extends QuestionHandler {

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for ORDERING question");
        }
        JsonNode statements = root.get("statements");
        if (statements == null || !statements.isArray() || statements.isEmpty()) {
            throw new ValidationException("COMPLIANCE must have at least one statement");
        }
        boolean hasCompliant = false;
        for (JsonNode statement : statements) {
            if (!statement.has("text") || statement.get("text").asText().isBlank()) {
                throw new ValidationException("Each statement must have a non-empty 'text");
            }
            if (statement.path("compliant").asBoolean(false)) {
                hasCompliant = true;
            }
        }
        if (!hasCompliant) {
            throw new ValidationException("At least one statement must be marked compliant");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt,
                              Question question,
                              JsonNode content,
                              JsonNode response) {
        Set<Integer> correct = StreamSupport.stream(content.get("statements").spliterator(), false)
                .filter(stmt -> stmt.path("compliant").asBoolean(false))
                .map(stmt -> stmt.get("id").asInt())
                .collect(Collectors.toSet());

        Set<Integer> selected = StreamSupport.stream(response.get("selectedStatementIds").spliterator(), false)
                .map(JsonNode::asInt)
                .collect(Collectors.toSet());

        boolean isCorrect = selected.equals(correct);
        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
