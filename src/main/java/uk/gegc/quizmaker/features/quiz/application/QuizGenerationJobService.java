package uk.gegc.quizmaker.features.quiz.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing quiz generation jobs
 */
public interface QuizGenerationJobService {

    /**
     * Create a new quiz generation job
     */
    QuizGenerationJob createJob(User user, UUID documentId, String requestData, int totalChunks, int estimatedTimeSeconds);

    /**
     * Get a job by ID and username
     */
    QuizGenerationJob getJobByIdAndUsername(UUID jobId, String username);

    /**
     * Get a job by ID
     */
    Optional<QuizGenerationJob> getJobById(UUID jobId);

    /**
     * Update job progress
     */
    QuizGenerationJob updateJobProgress(UUID jobId, int processedChunks, int currentChunk, int totalQuestionsGenerated);

    /**
     * Mark job as completed
     */
    QuizGenerationJob markJobCompleted(UUID jobId, UUID generatedQuizId);

    /**
     * Mark job as failed
     */
    QuizGenerationJob markJobFailed(UUID jobId, String errorMessage);

    /**
     * Cancel a job
     */
    QuizGenerationJob cancelJob(UUID jobId, String username);

    /**
     * Get all jobs for a user with pagination
     */
    Page<QuizGenerationJob> getJobsByUser(String username, Pageable pageable);

    /**
     * Get jobs by status
     */
    List<QuizGenerationJob> getJobsByStatus(GenerationStatus status);

    /**
     * Get active jobs (non-terminal status)
     */
    List<QuizGenerationJob> getActiveJobs();

    /**
     * Get jobs by document ID
     */
    List<QuizGenerationJob> getJobsByDocument(UUID documentId);

    /**
     * Get jobs created within a time range
     */
    List<QuizGenerationJob> getJobsByTimeRange(LocalDateTime start, LocalDateTime end);

    /**
     * Get job statistics for a user
     */
    JobStatistics getJobStatistics(String username);

    /**
     * Clean up old completed jobs
     */
    void cleanupOldJobs(int daysToKeep);

    /**
     * Get jobs that have been running too long
     */
    List<QuizGenerationJob> getStuckJobs(int maxDurationHours);

    /**
     * Clean up stale pending jobs
     */
    void cleanupStalePendingJobs();

    /**
     * Find and cancel stale pending job for a specific user if it exists and is older than the activation timeout.
     * Used for self-healing in the start generation path.
     * 
     * @param username the username to check
     * @return the cancelled job if one was found and cancelled, empty otherwise
     */
    Optional<QuizGenerationJob> findAndCancelStaleJobForUser(String username);

    /**
     * DTO for job statistics
     */
    record JobStatistics(
            long totalJobs,
            long completedJobs,
            long failedJobs,
            long cancelledJobs,
            long activeJobs,
            double averageGenerationTimeSeconds,
            long totalQuestionsGenerated,
            LocalDateTime lastJobCreated
    ) {
    }
} 