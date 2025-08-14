package uk.gegc.quizmaker.service.ai.parser;

import uk.gegc.quizmaker.exception.AIResponseParseException;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.List;

/**
 * Parser for converting AI responses into structured question objects
 */
public interface QuestionResponseParser {

    /**
     * Parse questions from AI response
     *
     * @param aiResponse   The raw response from AI
     * @param expectedType The expected question type
     * @return List of parsed questions
     * @throws AIResponseParseException if parsing fails
     */
    List<Question> parseQuestionsFromAIResponse(String aiResponse, QuestionType expectedType)
            throws AIResponseParseException;

    /**
     * Validate that a question has the correct structure for its type
     *
     * @param question The question to validate
     * @throws AIResponseParseException if validation fails
     */
    void validateQuestionContent(Question question) throws AIResponseParseException;

    /**
     * Extract questions from JSON response
     *
     * @param jsonResponse The JSON response from AI
     * @param questionType The type of questions to extract
     * @return List of extracted questions
     * @throws AIResponseParseException if extraction fails
     */
    List<Question> extractQuestionsFromJson(String jsonResponse, QuestionType questionType)
            throws AIResponseParseException;
} 