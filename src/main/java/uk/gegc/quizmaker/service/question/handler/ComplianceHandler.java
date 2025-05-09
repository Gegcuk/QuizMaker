package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

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
}
