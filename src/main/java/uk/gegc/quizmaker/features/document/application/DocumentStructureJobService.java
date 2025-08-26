package uk.gegc.quizmaker.features.document.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing document structure extraction jobs.
 * <p>
 * This service provides operations for creating, tracking, and managing
 * asynchronous document structure extraction jobs following the LRO pattern.
 * <p>
 * Implementation of Day 7 — REST API + Jobs from the chunk processing improvement plan.
 */
public interface DocumentStructureJobService {

    /**
     * Create a new document structure extraction job
     *
     * @param user the user requesting the extraction
     * @param documentId the document ID to extract structure from
     * @param strategy the extraction strategy (AI, REGEX, HYBRID)
     * @return the created job
     */
    DocumentStructureJob createJob(User user, UUID documentId, DocumentNode.Strategy strategy);

    /**
     * Get a job by ID and username for security
     *
     * @param jobId the job ID
     * @param username the username
     * @return the job if found and belongs to user
     */
    DocumentStructureJob getJobByIdAndUsername(UUID jobId, String username);

    /**
     * Get a job by ID
     *
     * @param jobId the job ID
     * @return optional job
     */
    Optional<DocumentStructureJob> getJobById(UUID jobId);

    /**
     * Update job progress
     *
     * @param jobId the job ID
     * @param progressPercentage the progress percentage (0-100)
     * @param currentPhase the current phase description
     * @return updated job
     */
    DocumentStructureJob updateJobProgress(UUID jobId, double progressPercentage, String currentPhase);

    /**
     * Mark job as completed
     *
     * @param jobId the job ID
     * @param nodesExtracted the number of nodes extracted
     * @param sourceVersionHash the source version hash
     * @return updated job
     */
    DocumentStructureJob markJobCompleted(UUID jobId, int nodesExtracted, String sourceVersionHash);

    /**
     * Mark job as failed
     *
     * @param jobId the job ID
     * @param errorMessage the error message
     * @param errorCode the error code (optional)
     * @return updated job
     */
    DocumentStructureJob markJobFailed(UUID jobId, String errorMessage, String errorCode);

    /**
     * Cancel a job
     *
     * @param jobId the job ID
     * @param username the username (for security)
     * @return updated job
     */
    DocumentStructureJob cancelJob(UUID jobId, String username);

    /**
     * Get all jobs for a user with pagination
     *
     * @param username the username
     * @param pageable pagination parameters
     * @return page of jobs
     */
    Page<DocumentStructureJob> getJobsByUser(String username, Pageable pageable);

    /**
     * Get jobs by status
     *
     * @param status the job status
     * @return list of jobs with the specified status
     */
    List<DocumentStructureJob> getJobsByStatus(DocumentStructureJob.StructureExtractionStatus status);

    /**
     * Get active jobs (non-terminal status)
     *
     * @return list of active jobs
     */
    List<DocumentStructureJob> getActiveJobs();

    /**
     * Get jobs by document ID
     *
     * @param documentId the document ID
     * @return list of jobs for the document
     */
    List<DocumentStructureJob> getJobsByDocument(UUID documentId);

    /**
     * Get jobs created within a time range
     *
     * @param start start time
     * @param end end time
     * @return list of jobs in the time range
     */
    List<DocumentStructureJob> getJobsByTimeRange(LocalDateTime start, LocalDateTime end);

    /**
     * Get job statistics for a user
     *
     * @param username the username
     * @return job statistics
     */
    JobStatistics getJobStatistics(String username);

    /**
     * Clean up old completed jobs
     *
     * @param daysToKeep number of days to keep completed jobs
     * @return number of jobs deleted
     */
    int cleanupOldJobs(int daysToKeep);

    /**
     * Get jobs that have been running too long
     *
     * @param maxDurationHours maximum duration in hours before considering stuck
     * @return list of stuck jobs
     */
    List<DocumentStructureJob> getStuckJobs(int maxDurationHours);

    /**
     * Clean up stale pending jobs
     *
     * @return number of jobs cleaned up
     */
    int cleanupStalePendingJobs();

    /**
     * Check if there's an active job for a document
     *
     * @param documentId the document ID
     * @return true if there's an active job
     */
    boolean hasActiveJobForDocument(UUID documentId);

    /**
     * Get the most recent completed job for a document
     *
     * @param documentId the document ID
     * @return optional most recent completed job
     */
    Optional<DocumentStructureJob> getMostRecentCompletedJobForDocument(UUID documentId);

    /**
     * Set extraction metrics for a completed job
     *
     * @param jobId the job ID
     * @param canonicalTextLength the canonical text length
     * @param preSegmentationWindows the number of pre-segmentation windows
     * @param outlineNodesExtracted the number of outline nodes extracted
     * @param alignmentSuccessRate the alignment success rate
     */
    void setExtractionMetrics(UUID jobId, int canonicalTextLength, int preSegmentationWindows,
                            int outlineNodesExtracted, double alignmentSuccessRate);

    /**
     * DTO for job statistics
     */
    record JobStatistics(
            long totalJobs,
            long completedJobs,
            long failedJobs,
            long cancelledJobs,
            long activeJobs,
            double averageExtractionTimeSeconds,
            long totalNodesExtracted,
            LocalDateTime lastJobCreated
    ) {
    }
}
