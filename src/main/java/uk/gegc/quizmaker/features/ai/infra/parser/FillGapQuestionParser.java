package uk.gegc.quizmaker.features.ai.infra.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.application.FillGapContentValidator;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

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
        
        // Use centralized validator
        FillGapContentValidator.ValidationResult result = FillGapContentValidator.validate(contentNode);
        if (!result.valid()) {
            throw new AIResponseParseException(result.errorMessage());
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
