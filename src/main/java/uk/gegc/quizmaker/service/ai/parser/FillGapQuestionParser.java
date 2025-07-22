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
 * Specialized parser for FILL_GAP questions
 */
@Component
@Slf4j
public class FillGapQuestionParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse FILL_GAP questions from JSON content
     */
    public List<Question> parseFillGapQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();

        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseFillGapQuestion(questionNode);
                questions.add(question);
            }
        }

        return questions;
    }

    /**
     * Parse a single FILL_GAP question
     */
    private Question parseFillGapQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateFillGapQuestionStructure(questionNode);
        return createQuestionFromNode(questionNode, QuestionType.FILL_GAP);
    }

    /**
     * Validate FILL_GAP question structure
     */
    private void validateFillGapQuestionStructure(JsonNode questionNode) throws AIResponseParseException {
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Missing 'content' field in FILL_GAP question");
        }

        JsonNode contentNode = questionNode.get("content");
        
        // Validate text field
        if (!contentNode.has("text") || contentNode.get("text").asText().trim().isEmpty()) {
            throw new AIResponseParseException("FILL_GAP question must have non-empty 'text' field");
        }

        // Validate gaps array
        if (!contentNode.has("gaps") || !contentNode.get("gaps").isArray()) {
            throw new AIResponseParseException("FILL_GAP question must have 'gaps' array");
        }

        JsonNode gapsNode = contentNode.get("gaps");
        if (gapsNode.size() < 1) {
            throw new AIResponseParseException("FILL_GAP question must have at least 1 gap");
        }

        // Validate each gap
        for (JsonNode gap : gapsNode) {
            if (!gap.has("id")) {
                throw new AIResponseParseException("Each gap must have 'id' field");
            }
            
            if (!gap.get("id").canConvertToInt()) {
                throw new AIResponseParseException("Gap 'id' must be an integer");
            }
            
            if (!gap.has("answer") || gap.get("answer").asText().trim().isEmpty()) {
                throw new AIResponseParseException("Each gap must have non-empty 'answer' field");
            }
        }

        // Validate that text contains gap markers (___)
        String text = contentNode.get("text").asText();
        if (!text.contains("___")) {
            log.warn("FILL_GAP text does not contain gap markers (___), but gaps are defined");
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