package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialized parser for OPEN questions
 */
@Component
@Slf4j
public class OpenQuestionParser {

    /**
     * Parse OPEN questions from JSON content
     */
    public List<Question> parseOpenQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();
        
        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseOpenQuestion(questionNode);
                questions.add(question);
            }
        }
        
        return questions;
    }

    /**
     * Parse a single OPEN question
     */
    private Question parseOpenQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateOpenQuestionStructure(questionNode);
        
        return createQuestionFromNode(questionNode, QuestionType.OPEN);
    }

    /**
     * Validate OPEN question structure
     */
    private void validateOpenQuestionStructure(JsonNode questionNode) throws AIResponseParseException {
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Missing 'content' field in OPEN question");
        }
        
        JsonNode contentNode = questionNode.get("content");
        if (!contentNode.has("answer") || contentNode.get("answer").asText().trim().isEmpty()) {
            throw new AIResponseParseException("OPEN question must have non-empty 'answer' field");
        }
        
        if (!questionNode.has("questionText") || questionNode.get("questionText").asText().trim().isEmpty()) {
            throw new AIResponseParseException("OPEN question must have non-empty 'questionText' field");
        }
        
        // Validate that the answer is substantial (not just a few words)
        String answer = contentNode.get("answer").asText();
        if (answer.split("\\s+").length < 10) {
            throw new AIResponseParseException("OPEN question answer should be substantial (at least 10 words)");
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