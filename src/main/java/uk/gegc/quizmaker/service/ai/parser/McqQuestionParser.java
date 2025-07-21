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
 * Specialized parser for MCQ (Multiple Choice Question) types
 */
@Component
@Slf4j
public class McqQuestionParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse MCQ_SINGLE questions from JSON content
     */
    public List<Question> parseMcqSingleQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();
        
        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseMcqSingleQuestion(questionNode);
                questions.add(question);
            }
        }
        
        return questions;
    }

    /**
     * Parse MCQ_MULTI questions from JSON content
     */
    public List<Question> parseMcqMultiQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();
        
        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseMcqMultiQuestion(questionNode);
                questions.add(question);
            }
        }
        
        return questions;
    }

    /**
     * Parse a single MCQ_SINGLE question
     */
    private Question parseMcqSingleQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateMcqQuestionStructure(questionNode);
        
        // Validate that exactly one option is correct
        JsonNode optionsNode = questionNode.get("content").get("options");
        int correctCount = 0;
        for (JsonNode option : optionsNode) {
            if (option.has("correct") && option.get("correct").asBoolean()) {
                correctCount++;
            }
        }
        
        if (correctCount != 1) {
            throw new AIResponseParseException(
                    String.format("MCQ_SINGLE must have exactly 1 correct answer, found %d", correctCount)
            );
        }
        
        return createQuestionFromNode(questionNode, QuestionType.MCQ_SINGLE);
    }

    /**
     * Parse a single MCQ_MULTI question
     */
    private Question parseMcqMultiQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateMcqQuestionStructure(questionNode);
        
        // Validate that at least one option is correct
        JsonNode optionsNode = questionNode.get("content").get("options");
        int correctCount = 0;
        for (JsonNode option : optionsNode) {
            if (option.has("correct") && option.get("correct").asBoolean()) {
                correctCount++;
            }
        }
        
        if (correctCount < 1) {
            throw new AIResponseParseException("MCQ_MULTI must have at least 1 correct answer");
        }
        
        return createQuestionFromNode(questionNode, QuestionType.MCQ_MULTI);
    }

    /**
     * Validate MCQ question structure
     */
    private void validateMcqQuestionStructure(JsonNode questionNode) throws AIResponseParseException {
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Missing 'content' field in MCQ question");
        }
        
        JsonNode contentNode = questionNode.get("content");
        if (!contentNode.has("options") || !contentNode.get("options").isArray()) {
            throw new AIResponseParseException("Missing or invalid 'options' array in MCQ question");
        }
        
        JsonNode optionsNode = contentNode.get("options");
        if (optionsNode.size() < 2) {
            throw new AIResponseParseException("MCQ question must have at least 2 options");
        }
        
        // Validate each option
        for (JsonNode option : optionsNode) {
            if (!option.has("text") || option.get("text").asText().trim().isEmpty()) {
                throw new AIResponseParseException("Each MCQ option must have non-empty 'text' field");
            }
            if (!option.has("correct")) {
                throw new AIResponseParseException("Each MCQ option must have 'correct' field");
            }
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
        
        if (questionNode.has("hint")) {
            question.setHint(questionNode.get("hint").asText());
        }
        if (questionNode.has("explanation")) {
            question.setExplanation(questionNode.get("explanation").asText());
        }
        
        return question;
    }
} 