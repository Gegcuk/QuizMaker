package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.List;

/**
 * Factory for creating and managing specialized question parsers
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuestionParserFactory {

    private final McqQuestionParser mcqQuestionParser;
    private final TrueFalseQuestionParser trueFalseQuestionParser;
    private final OpenQuestionParser openQuestionParser;

    /**
     * Parse questions based on the question type
     */
    public List<Question> parseQuestions(JsonNode contentNode, QuestionType questionType) 
            throws AIResponseParseException {
        
        log.debug("Parsing questions for type: {}", questionType);
        
        return switch (questionType) {
            case MCQ_SINGLE -> mcqQuestionParser.parseMcqSingleQuestions(contentNode);
            case MCQ_MULTI -> mcqQuestionParser.parseMcqMultiQuestions(contentNode);
            case TRUE_FALSE -> trueFalseQuestionParser.parseTrueFalseQuestions(contentNode);
            case OPEN -> openQuestionParser.parseOpenQuestions(contentNode);
            case FILL_GAP -> parseFillGapQuestions(contentNode);
            case ORDERING -> parseOrderingQuestions(contentNode);
            case COMPLIANCE -> parseComplianceQuestions(contentNode);
            case HOTSPOT -> parseHotspotQuestions(contentNode);
        };
    }

    /**
     * Parse FILL_GAP questions
     */
    private List<Question> parseFillGapQuestions(JsonNode contentNode) throws AIResponseParseException {
        // TODO: Implement specialized FILL_GAP parser
        log.warn("FILL_GAP parser not yet implemented, using generic parser");
        return parseGenericQuestions(contentNode, QuestionType.FILL_GAP);
    }

    /**
     * Parse ORDERING questions
     */
    private List<Question> parseOrderingQuestions(JsonNode contentNode) throws AIResponseParseException {
        // TODO: Implement specialized ORDERING parser
        log.warn("ORDERING parser not yet implemented, using generic parser");
        return parseGenericQuestions(contentNode, QuestionType.ORDERING);
    }

    /**
     * Parse COMPLIANCE questions
     */
    private List<Question> parseComplianceQuestions(JsonNode contentNode) throws AIResponseParseException {
        // TODO: Implement specialized COMPLIANCE parser
        log.warn("COMPLIANCE parser not yet implemented, using generic parser");
        return parseGenericQuestions(contentNode, QuestionType.COMPLIANCE);
    }

    /**
     * Parse HOTSPOT questions
     */
    private List<Question> parseHotspotQuestions(JsonNode contentNode) throws AIResponseParseException {
        // TODO: Implement specialized HOTSPOT parser
        log.warn("HOTSPOT parser not yet implemented, using generic parser");
        return parseGenericQuestions(contentNode, QuestionType.HOTSPOT);
    }

    /**
     * Generic parser for question types that don't have specialized parsers yet
     */
    private List<Question> parseGenericQuestions(JsonNode contentNode, QuestionType questionType) 
            throws AIResponseParseException {
        
        // This is a fallback implementation for question types without specialized parsers
        // It uses the basic parsing logic from QuestionResponseParserImpl
        
        if (!contentNode.has("questions") || !contentNode.get("questions").isArray()) {
            throw new AIResponseParseException("No 'questions' array found in content");
        }
        
        List<Question> questions = new java.util.ArrayList<>();
        
        for (JsonNode questionNode : contentNode.get("questions")) {
            try {
                Question question = parseGenericQuestion(questionNode, questionType);
                questions.add(question);
            } catch (Exception e) {
                log.warn("Failed to parse individual question, skipping: {}", e.getMessage());
            }
        }
        
        return questions;
    }

    /**
     * Parse a generic question node
     */
    private Question parseGenericQuestion(JsonNode questionNode, QuestionType questionType) 
            throws AIResponseParseException {
        
        // Basic validation
        if (!questionNode.has("questionText") || questionNode.get("questionText").asText().trim().isEmpty()) {
            throw new AIResponseParseException("Question must have non-empty 'questionText' field");
        }
        
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Question must have 'content' field");
        }
        
        // Create question object
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