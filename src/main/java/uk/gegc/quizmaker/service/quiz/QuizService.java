package uk.gegc.quizmaker.service.quiz;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.quiz.*;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.quiz.Visibility;

import java.util.List;
import java.util.UUID;

public interface QuizService {

    UUID createQuiz(String username, CreateQuizRequest request);

    Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria quizSearchCriteria);

    QuizDto getQuizById(UUID id);

    QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest updateQuizRequest);

    void deleteQuizById(String username, UUID quizId);

    void addQuestionToQuiz(String username, UUID quizId, UUID questionId);

    void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId);

    void addTagToQuiz(String username, UUID quizId, UUID tagId);

    void removeTagFromQuiz(String username, UUID quizId, UUID tagId);

    void changeCategory(String username, UUID quizId, UUID categoryId);

    QuizDto setVisibility(String name, UUID quizId, Visibility visibility);

    QuizDto setStatus(String name, UUID quizId, QuizStatus status);

    Page<QuizDto> getPublicQuizzes(Pageable pageable);

    void deleteQuizzesByIds(String name, List<UUID> quizIds);

    BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String name, BulkQuizUpdateRequest request);

    /**
     * Generate a quiz from document chunks using AI
     *
     * @param username The username of the user requesting quiz generation
     * @param request  The quiz generation request containing document ID and parameters
     * @return QuizGenerationResponse with generation details
     */
    QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request);

    /**
     * Start an asynchronous quiz generation job
     *
     * @param username The username of the user requesting quiz generation
     * @param request  The quiz generation request containing document ID and parameters
     * @return QuizGenerationResponse with job ID and initial status
     */
    QuizGenerationResponse startQuizGeneration(String username, GenerateQuizFromDocumentRequest request);

    /**
     * Get the status of a quiz generation job
     *
     * @param jobId    The generation job ID
     * @param username The username requesting the status
     * @return QuizGenerationStatus with current progress and details
     */
    QuizGenerationStatus getGenerationStatus(UUID jobId, String username);

    /**
     * Get the generated quiz from a completed generation job
     *
     * @param jobId    The generation job ID
     * @param username The username requesting the quiz
     * @return QuizDto of the generated quiz
     */
    QuizDto getGeneratedQuiz(UUID jobId, String username);

    /**
     * Cancel an active quiz generation job
     *
     * @param jobId    The generation job ID to cancel
     * @param username The username requesting the cancellation
     * @return QuizGenerationStatus with updated status
     */
    QuizGenerationStatus cancelGenerationJob(UUID jobId, String username);

    /**
     * Get a paginated list of generation jobs for a user
     *
     * @param username The username to get jobs for
     * @param pageable Pagination parameters
     * @return Page of QuizGenerationStatus objects
     */
    Page<QuizGenerationStatus> getGenerationJobs(String username, Pageable pageable);

    /**
     * Get statistics about generation jobs for a user
     *
     * @param username The username to get statistics for
     * @return JobStatistics with success rates and timing information
     */
    QuizGenerationJobService.JobStatistics getGenerationJobStatistics(String username);
}
