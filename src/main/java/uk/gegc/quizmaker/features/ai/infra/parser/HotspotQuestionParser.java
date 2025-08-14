package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialized parser for HOTSPOT questions
 */
@Component
@Slf4j
public class HotspotQuestionParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse HOTSPOT questions from JSON content
     */
    public List<Question> parseHotspotQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();

        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseHotspotQuestion(questionNode);
                questions.add(question);
            }
        }

        return questions;
    }

    /**
     * Parse a single HOTSPOT question
     */
    private Question parseHotspotQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateHotspotQuestionStructure(questionNode);
        return createQuestionFromNode(questionNode, QuestionType.HOTSPOT);
    }

    /**
     * Validate HOTSPOT question structure
     */
    private void validateHotspotQuestionStructure(JsonNode questionNode) throws AIResponseParseException {
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Missing 'content' field in HOTSPOT question");
        }

        JsonNode contentNode = questionNode.get("content");
        
        // Validate imageUrl
        if (!contentNode.has("imageUrl")) {
            throw new AIResponseParseException("HOTSPOT question must have 'imageUrl' field");
        }
        
        if (contentNode.get("imageUrl").asText().trim().isEmpty()) {
            throw new AIResponseParseException("HOTSPOT question must have non-empty 'imageUrl'");
        }
        
        // Validate regions array
        if (!contentNode.has("regions") || !contentNode.get("regions").isArray()) {
            throw new AIResponseParseException("HOTSPOT question must have 'regions' array");
        }

        JsonNode regionsNode = contentNode.get("regions");
        if (regionsNode.size() < 2) {
            throw new AIResponseParseException("HOTSPOT question must have at least 2 regions");
        }

        if (regionsNode.size() > 6) {
            throw new AIResponseParseException("HOTSPOT question cannot have more than 6 regions");
        }

        // Validate each region and check for at least one correct region
        boolean hasCorrectRegion = false;
        List<Integer> ids = new ArrayList<>();

        for (JsonNode region : regionsNode) {
            // Validate ID
            if (!region.has("id")) {
                throw new AIResponseParseException("Each region must have 'id' field");
            }
            
            if (!region.get("id").canConvertToInt()) {
                throw new AIResponseParseException("Region 'id' must be an integer");
            }
            
            int id = region.get("id").asInt();
            if (ids.contains(id)) {
                throw new AIResponseParseException("Region IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);
            
            // Validate coordinates
            for (String coordinate : new String[]{"x", "y", "width", "height"}) {
                if (!region.has(coordinate)) {
                    throw new AIResponseParseException("Each region must have '" + coordinate + "' field");
                }
                
                if (!region.get(coordinate).canConvertToInt()) {
                    throw new AIResponseParseException("Region '" + coordinate + "' must be an integer");
                }
                
                int value = region.get(coordinate).asInt();
                if (value < 0) {
                    throw new AIResponseParseException("Region '" + coordinate + "' must be non-negative");
                }
            }
            
            // Validate correct flag
            if (!region.has("correct")) {
                throw new AIResponseParseException("Each region must have 'correct' field");
            }
            
            if (!region.get("correct").isBoolean()) {
                throw new AIResponseParseException("Region 'correct' field must be a boolean");
            }
            
            // Check if at least one region is correct
            if (region.get("correct").asBoolean()) {
                hasCorrectRegion = true;
            }
        }

        if (!hasCorrectRegion) {
            throw new AIResponseParseException("At least one region must be marked as correct");
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