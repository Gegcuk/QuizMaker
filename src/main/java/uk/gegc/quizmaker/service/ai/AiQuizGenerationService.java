package uk.gegc.quizmaker.service.ai;

import uk.gegc.quizmaker.dto.quiz.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for generating quizzes from document chunks using AI
 */
public interface AiQuizGenerationService {

    /**
     * Generate a complete quiz from document chunks asynchronously
     *
     * @param jobId   The generation job ID for tracking
     * @param request The quiz generation request containing document ID and parameters
     */
    void generateQuizFromDocumentAsync(UUID jobId, GenerateQuizFromDocumentRequest request);

    /**
     * Generate a complete quiz from document chunks asynchronously with job data
     *
     * @param job     The generation job entity
     * @param request The quiz generation request containing document ID and parameters
     */
    void generateQuizFromDocumentAsync(uk.gegc.quizmaker.model.quiz.QuizGenerationJob job, GenerateQuizFromDocumentRequest request);

    /**
     * Generate questions from a single document chunk asynchronously
     *
     * @param chunk            The document chunk to generate questions from
     * @param questionsPerType Map of question types to number of questions to generate
     * @param difficulty       The difficulty level for the questions
     * @return CompletableFuture containing the list of generated questions
     */
    CompletableFuture<List<Question>> generateQuestionsFromChunk(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty
    );

    /**
     * Generate questions of a specific type from chunk content
     *
     * @param chunkContent  The content of the document chunk
     * @param questionType  The type of questions to generate
     * @param questionCount The number of questions to generate
     * @param difficulty    The difficulty level for the questions
     * @return List of generated questions
     */
    List<Question> generateQuestionsByType(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty
    );

    /**
     * Validate that the document is suitable for quiz generation
     *
     * @param documentId The document ID to validate
     * @param username   The username requesting the generation
     * @throws IllegalArgumentException if document is not suitable
     */
    void validateDocumentForGeneration(UUID documentId, String username);

    /**
     * Calculate estimated generation time based on document size and question count
     *
     * @param totalChunks      Number of chunks to process
     * @param questionsPerType Map of question types to counts
     * @return Estimated time in seconds
     */
    int calculateEstimatedGenerationTime(int totalChunks, Map<QuestionType, Integer> questionsPerType);

    /**
     * Calculate total number of chunks for a document based on the request scope
     *
     * @param documentId The document ID
     * @param request    The quiz generation request
     * @return Total number of chunks to process
     */
    int calculateTotalChunks(UUID documentId, GenerateQuizFromDocumentRequest request);
} 