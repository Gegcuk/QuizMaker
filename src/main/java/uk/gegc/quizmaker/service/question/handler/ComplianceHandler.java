package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.question.Answer;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class ComplianceHandler extends QuestionHandler {

    @Override
    public QuestionType supportedType() {
        return QuestionType.COMPLIANCE;
    }

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for COMPLIANCE question");
        }
        JsonNode statements = root.get("statements");
        if (statements == null || !statements.isArray() || statements.size() < 2) {
            throw new ValidationException("COMPLIANCE must have at least 2 statements");
        }
        if (statements.size() > 6) {
            throw new ValidationException("COMPLIANCE must have at most 6 statements");
        }
        boolean hasCompliant = false;
        Set<Integer> ids = new java.util.HashSet<>();
        for (JsonNode statement : statements) {
            // Validate id field
            if (!statement.has("id")) {
                throw new ValidationException("Each statement must have an 'id' field");
            }
            if (!statement.get("id").canConvertToInt()) {
                throw new ValidationException("Statement 'id' must be an integer");
            }
            int id = statement.get("id").asInt();
            if (ids.contains(id)) {
                throw new ValidationException("Statement IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);
            
            // Validate text field
            if (!statement.has("text") || statement.get("text").asText().isBlank()) {
                throw new ValidationException("Each statement must have a non-empty 'text'");
            }
            
            // Validate compliant field
            if (!statement.has("compliant")) {
                throw new ValidationException("Each statement must have a 'compliant' field");
            }
            if (!statement.get("compliant").isBoolean()) {
                throw new ValidationException("Statement 'compliant' field must be a boolean");
            }
            if (statement.get("compliant").asBoolean()) {
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
        Set<Integer> correct = StreamSupport.stream(
                        content.get("statements").spliterator(), false)
                .filter(stmt -> stmt.path("compliant").asBoolean(false))
                .map(stmt -> stmt.get("id").asInt())
                .collect(Collectors.toSet());

        JsonNode selectedNode = response.get("selectedStatementIds");
        List<Integer> selectedList = selectedNode != null && selectedNode.isArray()
                ? StreamSupport.stream(selectedNode.spliterator(), false)
                        .filter(id -> id.canConvertToInt())
                        .map(JsonNode::asInt)
                        .toList()
                : List.of();

        // Check for duplicates in the response
        Set<Integer> selectedSet = new java.util.HashSet<>(selectedList);
        boolean hasDuplicates = selectedList.size() != selectedSet.size();

        // If there are duplicates, the answer is incorrect
        if (hasDuplicates) {
            Answer ans = new Answer();
            ans.setIsCorrect(false);
            ans.setScore(0.0);
            return ans;
        }

        boolean isCorrect = selectedSet.equals(correct);
        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
