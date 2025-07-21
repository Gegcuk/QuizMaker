package uk.gegc.quizmaker.service.ai.parser.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.ai.parser.QuestionResponseParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of QuestionResponseParser for parsing AI responses
 */
@Component
@Slf4j
public class QuestionResponseParserImpl implements QuestionResponseParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Question> parseQuestionsFromAIResponse(String aiResponse, QuestionType expectedType) 
            throws AIResponseParseException {
        try {
            // TODO: Implement proper parsing logic
            log.warn("AI response parsing not yet fully implemented for type: {}", expectedType);
            
            // For now, return empty list to avoid compilation errors
            return new ArrayList<>();
            
        } catch (Exception e) {
            throw new AIResponseParseException("Failed to parse AI response: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateQuestionContent(Question question) throws AIResponseParseException {
        // TODO: Implement question validation
        log.debug("Question validation not yet implemented");
    }

    @Override
    public List<Question> extractQuestionsFromJson(String jsonResponse, QuestionType questionType) 
            throws AIResponseParseException {
        try {
            // TODO: Implement JSON extraction logic
            log.warn("JSON extraction not yet implemented for type: {}", questionType);
            return new ArrayList<>();
            
        } catch (Exception e) {
            throw new AIResponseParseException("Failed to extract questions from JSON: " + e.getMessage(), e);
        }
    }
} 