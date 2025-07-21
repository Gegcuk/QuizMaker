package uk.gegc.quizmaker.service.quiz;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.quiz.QuizGenerationStatus;
import uk.gegc.quizmaker.model.quiz.GenerationStatus;
import uk.gegc.quizmaker.model.quiz.QuizGenerationJob;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing quiz generation jobs
 */
public interface QuizGenerationJobService {

    /**
     * Create a new generation job
     */
    QuizGenerationJob createJob(UUID documentId, String username, String requestData);

    /**
     * Get a job by ID
     */
    Optional<QuizGenerationJob> getJobById(UUID jobId);

    /**
     * Get a job by ID with user authorization
     */
    QuizGenerationJob getJobByIdAndUsername(UUID jobId, String username);

    /**
     * Get all jobs for a user
     */
    List<QuizGenerationJob> getJobsByUsername(String username);

    /**
     * Get jobs for a user with pagination
     */
    Page<QuizGenerationJob> getJobsByUsername(String username, Pageable pageable);

    /**
     * Get active jobs for a user
     */
    List<QuizGenerationJob> getActiveJobsByUsername(String username);

    /**
     * Update job progress
     */
    void updateJobProgress(UUID jobId, int processedChunks, String currentChunk);

    /**
     * Mark job as completed
     */
    void markJobCompleted(UUID jobId, UUID generatedQuizId, int totalQuestions);

    /**
     * Mark job as failed
     */
    void markJobFailed(UUID jobId, String errorMessage);

    /**
     * Cancel a job
     */
    void cancelJob(UUID jobId, String username);

    /**
     * Get job status as DTO
     */
    QuizGenerationStatus getJobStatus(UUID jobId, String username);

    /**
     * Find stuck jobs (jobs that have been processing for too long)
     */
    List<QuizGenerationJob> findStuckJobs();

    /**
     * Clean up old completed jobs
     */
    void cleanupOldJobs();

    /**
     * Get job statistics for a user
     */
    JobStatistics getJobStatistics(String username);

    /**
     * Check if user has active jobs
     */
    boolean hasActiveJobs(String username);

    /**
     * Get the most recent job for a document
     */
    Optional<QuizGenerationJob> getMostRecentJobForDocument(UUID documentId);

    /**
     * Count jobs by status for a user
     */
    long countJobsByStatus(String username, GenerationStatus status);

    /**
     * Job statistics record
     */
    record JobStatistics(
            long totalJobs,
            long completedJobs,
            long failedJobs,
            long activeJobs,
            double successRate,
            long averageGenerationTimeSeconds
    ) {}
} 