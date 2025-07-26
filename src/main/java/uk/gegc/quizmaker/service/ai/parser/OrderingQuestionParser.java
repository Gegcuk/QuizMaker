package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialized parser for ORDERING questions
 */
@Component
@Slf4j
public class OrderingQuestionParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse ORDERING questions from JSON content
     */
    public List<Question> parseOrderingQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();

        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseOrderingQuestion(questionNode);
                questions.add(question);
            }
        }

        return questions;
    }

    /**
     * Parse a single ORDERING question
     */
    private Question parseOrderingQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateOrderingQuestionStructure(questionNode);
        return createQuestionFromNode(questionNode, QuestionType.ORDERING);
    }

    /**
     * Validate ORDERING question structure
     */
    private void validateOrderingQuestionStructure(JsonNode questionNode) throws AIResponseParseException {
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Missing 'content' field in ORDERING question");
        }

        JsonNode contentNode = questionNode.get("content");
        
        // Validate items array
        if (!contentNode.has("items") || !contentNode.get("items").isArray()) {
            throw new AIResponseParseException("ORDERING question must have 'items' array");
        }

        JsonNode itemsNode = contentNode.get("items");
        if (itemsNode.size() < 2) {
            throw new AIResponseParseException("ORDERING question must have at least 2 items");
        }

        if (itemsNode.size() > 10) {
            throw new AIResponseParseException("ORDERING question cannot have more than 10 items");
        }

        // Validate each item
        for (JsonNode item : itemsNode) {
            if (!item.has("id")) {
                throw new AIResponseParseException("Each item must have 'id' field");
            }
            
            if (!item.get("id").canConvertToInt()) {
                throw new AIResponseParseException("Item 'id' must be an integer");
            }
            
            if (!item.has("text") || item.get("text").asText().trim().isEmpty()) {
                throw new AIResponseParseException("Each item must have non-empty 'text' field");
            }
        }

        // Validate that IDs are unique and sequential
        List<Integer> ids = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            int id = item.get("id").asInt();
            if (ids.contains(id)) {
                throw new AIResponseParseException("Item IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);
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
                question.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.valueOf(difficultyStr));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid difficulty value '{}', using MEDIUM as default", questionNode.get("difficulty").asText());
                question.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.MEDIUM);
            }
        } else {
            log.warn("No difficulty field found in question, using MEDIUM as default");
            question.setDifficulty(uk.gegc.quizmaker.model.question.Difficulty.MEDIUM);
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