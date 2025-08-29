package uk.gegc.quizmaker.features.ai.infra.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of QuestionResponseParser for parsing AI responses
 */
@Component
@Slf4j
public class QuestionResponseParserImpl implements QuestionResponseParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final QuestionHandlerFactory questionHandlerFactory;
    private final QuestionParserFactory questionParserFactory;

    public QuestionResponseParserImpl(QuestionHandlerFactory questionHandlerFactory,
                                      QuestionParserFactory questionParserFactory) {
        this.questionHandlerFactory = questionHandlerFactory;
        this.questionParserFactory = questionParserFactory;
    }

    @Override
    public List<Question> parseQuestionsFromAIResponse(String aiResponse, QuestionType expectedType)
            throws AIResponseParseException {
        try {
            log.debug("Parsing AI response for question type: {}", expectedType);

            // Clean the response - remove any markdown formatting
            String cleanedResponse = cleanAIResponse(aiResponse);

            // Try to extract JSON from the response
            String jsonContent = extractJsonFromResponse(cleanedResponse);

            // Parse the JSON and extract questions using the factory
            JsonNode rootNode = objectMapper.readTree(jsonContent);
            List<Question> questions = questionParserFactory.parseQuestions(rootNode, expectedType);

            // Validate each question
            for (Question question : questions) {
                validateQuestionContent(question);
            }

            log.debug("Successfully parsed {} questions of type {}", questions.size(), expectedType);
            return questions;

        } catch (Exception e) {
            log.error("Failed to parse AI response for type: {}", expectedType, e);
            throw new AIResponseParseException("Failed to parse AI response: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateQuestionContent(Question question) throws AIResponseParseException {
        try {
            log.debug("Validating question content for type: {}", question.getType());

            // Use the existing question handler factory to validate content
            var handler = questionHandlerFactory.getHandler(question.getType());

            // Create a content request for validation
            var contentRequest = new EntityQuestionContentRequest(
                    question.getType(),
                    objectMapper.readTree(question.getContent())
            );

            // Validate using the existing handler
            handler.validateContent(contentRequest);

            log.debug("Question validation successful for type: {}", question.getType());

        } catch (Exception e) {
            log.error("Question validation failed for type: {}", question.getType(), e);
            throw new AIResponseParseException("Question validation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Question> extractQuestionsFromJson(String jsonResponse, QuestionType questionType)
            throws AIResponseParseException {
        try {
            log.debug("Extracting questions from JSON for type: {}", questionType);

            JsonNode rootNode = objectMapper.readTree(jsonResponse);

            // Handle different JSON structures
            JsonNode questionsNode = rootNode.get("questions");
            if (questionsNode == null) {
                // Try direct array format
                if (rootNode.isArray()) {
                    questionsNode = rootNode;
                } else {
                    throw new AIResponseParseException("No 'questions' array found in JSON response");
                }
            }

            if (!questionsNode.isArray()) {
                throw new AIResponseParseException("Questions node is not an array");
            }

            List<Question> questions = new ArrayList<>();

            for (JsonNode questionNode : questionsNode) {
                try {
                    Question question = parseQuestionNode(questionNode, questionType);
                    questions.add(question);
                } catch (Exception e) {
                    log.warn("Failed to parse individual question, skipping: {}", e.getMessage());
                    // Continue with other questions instead of failing completely
                }
            }

            log.debug("Extracted {} questions from JSON", questions.size());
            return questions;

        } catch (JsonProcessingException e) {
            throw new AIResponseParseException("Invalid JSON format: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AIResponseParseException("Failed to extract questions from JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a single question node from JSON
     */
    private Question parseQuestionNode(JsonNode questionNode, QuestionType expectedType)
            throws AIResponseParseException {

        // Extract basic question fields
        String questionText = getRequiredStringField(questionNode, "questionText");
        String typeStr = getRequiredStringField(questionNode, "type");
        String difficultyStr = getRequiredStringField(questionNode, "difficulty");

        // Validate question type
        QuestionType actualType = QuestionType.valueOf(typeStr);
        if (actualType != expectedType) {
            throw new AIResponseParseException(
                    String.format("Expected question type %s but got %s", expectedType, actualType)
            );
        }

        // Parse difficulty
        Difficulty difficulty = Difficulty.valueOf(difficultyStr);

        // Extract content
        JsonNode contentNode = questionNode.get("content");
        if (contentNode == null) {
            throw new AIResponseParseException("Missing 'content' field in question");
        }

        // Extract optional fields
        String hint = getOptionalStringField(questionNode, "hint");
        String explanation = getOptionalStringField(questionNode, "explanation");

        // Create question object
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setQuestionText(questionText);
        question.setType(actualType);
        question.setDifficulty(difficulty);
        question.setContent(contentNode.toString());
        question.setHint(hint);
        question.setExplanation(explanation);

        return question;
    }

    /**
     * Clean AI response by removing markdown formatting and extracting JSON
     */
    private String cleanAIResponse(String aiResponse) {
        String cleaned = aiResponse.trim();

        // Remove markdown code blocks
        cleaned = cleaned.replaceAll("```json\\s*", "");
        cleaned = cleaned.replaceAll("```\\s*", "");

        // Remove any text before the first {
        int jsonStart = cleaned.indexOf('{');
        if (jsonStart > 0) {
            cleaned = cleaned.substring(jsonStart);
        }

        // Remove any text after the last }
        int jsonEnd = cleaned.lastIndexOf('}');
        if (jsonEnd >= 0 && jsonEnd < cleaned.length() - 1) {
            cleaned = cleaned.substring(0, jsonEnd + 1);
        }

        return cleaned.trim();
    }

    /**
     * Extract JSON content from AI response
     */
    private String extractJsonFromResponse(String response) throws AIResponseParseException {
        // Try to find JSON object or array
        int startBrace = response.indexOf('{');
        int startBracket = response.indexOf('[');

        if (startBrace == -1 && startBracket == -1) {
            throw new AIResponseParseException("No JSON content found in response");
        }

        // Determine if it's an object or array
        boolean isObject = startBrace != -1 && (startBracket == -1 || startBrace < startBracket);
        char startChar = isObject ? '{' : '[';
        char endChar = isObject ? '}' : ']';

        int start = isObject ? startBrace : startBracket;
        int braceCount = 0;
        int end = -1;

        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == startChar) {
                braceCount++;
            } else if (c == endChar) {
                braceCount--;
                if (braceCount == 0) {
                    end = i;
                    break;
                }
            }
        }

        if (end == -1) {
            throw new AIResponseParseException("Invalid JSON structure - unmatched braces");
        }

        return response.substring(start, end + 1);
    }

    /**
     * Get a required string field from JSON node
     */
    private String getRequiredStringField(JsonNode node, String fieldName) throws AIResponseParseException {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || !fieldNode.isTextual()) {
            throw new AIResponseParseException("Missing or invalid required field: " + fieldName);
        }
        return fieldNode.asText();
    }

    /**
     * Get an optional string field from JSON node
     */
    private String getOptionalStringField(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        return (fieldNode != null && fieldNode.isTextual()) ? fieldNode.asText() : null;
    }
} 