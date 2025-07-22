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
 * Specialized parser for TRUE_FALSE questions
 */
@Component
@Slf4j
public class TrueFalseQuestionParser {

    /**
     * Parse TRUE_FALSE questions from JSON content
     */
    public List<Question> parseTrueFalseQuestions(JsonNode contentNode) throws AIResponseParseException {
        List<Question> questions = new ArrayList<>();

        if (contentNode.has("questions") && contentNode.get("questions").isArray()) {
            for (JsonNode questionNode : contentNode.get("questions")) {
                Question question = parseTrueFalseQuestion(questionNode);
                questions.add(question);
            }
        }

        return questions;
    }

    /**
     * Parse a single TRUE_FALSE question
     */
    private Question parseTrueFalseQuestion(JsonNode questionNode) throws AIResponseParseException {
        validateTrueFalseQuestionStructure(questionNode);

        return createQuestionFromNode(questionNode, QuestionType.TRUE_FALSE);
    }

    /**
     * Validate TRUE_FALSE question structure
     */
    private void validateTrueFalseQuestionStructure(JsonNode questionNode) throws AIResponseParseException {
        if (!questionNode.has("content")) {
            throw new AIResponseParseException("Missing 'content' field in TRUE_FALSE question");
        }

        JsonNode contentNode = questionNode.get("content");
        if (!contentNode.has("answer") || !contentNode.get("answer").isBoolean()) {
            throw new AIResponseParseException("TRUE_FALSE question must have boolean 'answer' field");
        }

        if (!questionNode.has("questionText") || questionNode.get("questionText").asText().trim().isEmpty()) {
            throw new AIResponseParseException("TRUE_FALSE question must have non-empty 'questionText' field");
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