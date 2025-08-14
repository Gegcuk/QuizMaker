package uk.gegc.quizmaker.features.ai.infra.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialized parser for COMPLIANCE questions
 */
@Component
@Slf4j
public class ComplianceQuestionParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse COMPLIANCE questions from JSON content
     */
    public List<Question> parseComplianceQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();

        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseComplianceQuestion(questionNode);
                questions.add(question);
            }
        }

        return questions;
    }

    /**
     * Parse a single COMPLIANCE question
     */
    private Question parseComplianceQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateComplianceQuestionStructure(questionNode);
        return createQuestionFromNode(questionNode, QuestionType.COMPLIANCE);
    }

    /**
     * Validate COMPLIANCE question structure
     */
    private void validateComplianceQuestionStructure(JsonNode questionNode) throws AIResponseParseException {
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Missing 'content' field in COMPLIANCE question");
        }

        JsonNode contentNode = questionNode.get("content");
        
        // Validate statements array
        if (!contentNode.has("statements") || !contentNode.get("statements").isArray()) {
            throw new AIResponseParseException("COMPLIANCE question must have 'statements' array");
        }

        JsonNode statementsNode = contentNode.get("statements");
        if (statementsNode.size() < 2) {
            throw new AIResponseParseException("COMPLIANCE question must have at least 2 statements");
        }

        if (statementsNode.size() > 6) {
            throw new AIResponseParseException("COMPLIANCE question cannot have more than 6 statements");
        }

        // Validate each statement and check for at least one compliant statement
        boolean hasCompliantStatement = false;
        List<Integer> ids = new ArrayList<>();

        for (JsonNode statement : statementsNode) {
            // Validate ID
            if (!statement.has("id")) {
                throw new AIResponseParseException("Each statement must have 'id' field");
            }
            
            if (!statement.get("id").canConvertToInt()) {
                throw new AIResponseParseException("Statement 'id' must be an integer");
            }
            
            int id = statement.get("id").asInt();
            if (ids.contains(id)) {
                throw new AIResponseParseException("Statement IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);
            
            // Validate text
            if (!statement.has("text") || statement.get("text").asText().trim().isEmpty()) {
                throw new AIResponseParseException("Each statement must have non-empty 'text' field");
            }
            
            // Validate compliant flag
            if (!statement.has("compliant")) {
                throw new AIResponseParseException("Each statement must have 'compliant' field");
            }
            
            if (!statement.get("compliant").isBoolean()) {
                throw new AIResponseParseException("Statement 'compliant' field must be a boolean");
            }
            
            // Check if at least one statement is compliant
            if (statement.get("compliant").asBoolean()) {
                hasCompliantStatement = true;
            }
        }

        if (!hasCompliantStatement) {
            throw new AIResponseParseException("At least one statement must be marked as compliant");
        }
    }

    /**
     * Create a Question object from JSON node
     */
    private Question createQuestionFromNode(JsonNode questionNode, QuestionType questionType) {
        Question question = new Question();
        question.setQuestionText(questionNode.get("questionText").asText());
        question.setType(questionType);
        question.setContent(questionNode.get("content").toString());

        // Set difficulty - use the one from the AI response or default to MEDIUM
        if (questionNode.has("difficulty")) {
            try {
                String difficultyStr = questionNode.get("difficulty").asText();
                question.setDifficulty(Difficulty.valueOf(difficultyStr));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid difficulty value '{}', using MEDIUM as default", questionNode.get("difficulty").asText());
                question.setDifficulty(Difficulty.MEDIUM);
            }
        } else {
            log.warn("No difficulty field found in question, using MEDIUM as default");
            question.setDifficulty(Difficulty.MEDIUM);
        }

        if (questionNode.has("hint")) {
            question.setHint(questionNode.get("hint").asText());
        }
        if (questionNode.has("explanation")) {
            question.setExplanation(questionNode.get("explanation").asText());
        }

        return question;
    }
} 