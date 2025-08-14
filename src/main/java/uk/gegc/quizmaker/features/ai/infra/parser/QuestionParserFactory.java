package uk.gegc.quizmaker.service.ai.parser;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

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
    private final FillGapQuestionParser fillGapQuestionParser;
    private final OrderingQuestionParser orderingQuestionParser;
    private final ComplianceQuestionParser complianceQuestionParser;
    private final HotspotQuestionParser hotspotQuestionParser;

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
            case FILL_GAP -> fillGapQuestionParser.parseFillGapQuestions(contentNode);
            case ORDERING -> orderingQuestionParser.parseOrderingQuestions(contentNode);
            case COMPLIANCE -> complianceQuestionParser.parseComplianceQuestions(contentNode);
            case HOTSPOT -> hotspotQuestionParser.parseHotspotQuestions(contentNode);
            case MATCHING -> parseGenericQuestions(contentNode, QuestionType.MATCHING);
        };
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