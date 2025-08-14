package uk.gegc.quizmaker.features.ai.application;

import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

/**
 * Service for building AI prompts for quiz generation
 */
public interface PromptTemplateService {

    /**
     * Build a prompt for generating questions from a document chunk
     *
     * @param chunkContent  The content of the document chunk
     * @param questionType  The type of questions to generate
     * @param questionCount The number of questions to generate
     * @param difficulty    The difficulty level for the questions
     * @return Formatted prompt string for AI
     */
    String buildPromptForChunk(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty
    );

    /**
     * Load a prompt template from resources
     *
     * @param templateName The name of the template file
     * @return The template content
     */
    String loadPromptTemplate(String templateName);

    /**
     * Build a system prompt for quiz generation
     *
     * @return System prompt for AI
     */
    String buildSystemPrompt();
} 