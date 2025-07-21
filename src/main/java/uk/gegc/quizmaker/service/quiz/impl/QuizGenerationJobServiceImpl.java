package uk.gegc.quizmaker.service.quiz.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.quiz.GenerationStatus;
import uk.gegc.quizmaker.model.quiz.QuizGenerationJob;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizGenerationJobRepository;
import uk.gegc.quizmaker.service.quiz.QuizGenerationJobService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of QuizGenerationJobService for managing quiz generation jobs
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class QuizGenerationJobServiceImpl implements QuizGenerationJobService {

    private final QuizGenerationJobRepository jobRepository;

    @Override
    public QuizGenerationJob createJob(User user, UUID documentId, String requestData, int totalChunks, int estimatedTimeSeconds) {
        log.info("Creating quiz generation job for user: {}, document: {}, chunks: {}",
                user.getUsername(), documentId, totalChunks);

        QuizGenerationJob job = new QuizGenerationJob();
        job.setUser(user);
        job.setDocumentId(documentId);
        job.setRequestData(requestData);
        job.setTotalChunks(totalChunks);
        job.setProcessedChunks(0);
        job.setCurrentChunk("0");
        job.setTotalQuestionsGenerated(0);
        job.setStatus(GenerationStatus.PENDING);
        job.setEstimatedCompletion(LocalDateTime.now().plusSeconds(estimatedTimeSeconds));

        QuizGenerationJob savedJob = jobRepository.save(job);
        log.info("Created quiz generation job with ID: {}", savedJob.getId());
        return savedJob;
    }

    @Override
    @Transactional(readOnly = true)
    public QuizGenerationJob getJobByIdAndUsername(UUID jobId, String username) {
        log.debug("Getting job by ID: {} for user: {}", jobId, username);

        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz generation job not found with ID: " + jobId));

        if (!job.getUser().getUsername().equals(username)) {
            log.warn("User {} attempted to access job {} owned by user {}",
                    username, jobId, job.getUser().getUsername());
            throw new ValidationException("Access denied: job does not belong to user");
        }

        return job;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuizGenerationJob> getJobById(UUID jobId) {
        log.debug("Getting job by ID: {}", jobId);
        return jobRepository.findById(jobId);
    }

    @Override
    public QuizGenerationJob updateJobProgress(UUID jobId, int processedChunks, int currentChunk, int totalQuestionsGenerated) {
        log.debug("Updating job progress for job: {}, processed: {}, current: {}, questions: {}",
                jobId, processedChunks, currentChunk, totalQuestionsGenerated);

        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz generation job not found with ID: " + jobId));

        if (job.isTerminal()) {
            log.warn("Attempted to update progress for terminal job: {}", jobId);
            throw new ValidationException("Cannot update progress for job in terminal state: " + job.getStatus());
        }

        job.updateProgress(processedChunks, String.valueOf(currentChunk));
        job.setTotalQuestionsGenerated(totalQuestionsGenerated);
        job.setStatus(GenerationStatus.PROCESSING);

        QuizGenerationJob updatedJob = jobRepository.save(job);
        log.debug("Updated job progress for job: {}", jobId);
        return updatedJob;
    }

    @Override
    public QuizGenerationJob markJobCompleted(UUID jobId, UUID generatedQuizId) {
        log.info("Marking job as completed: {}, generated quiz: {}", jobId, generatedQuizId);

        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz generation job not found with ID: " + jobId));

        if (job.isTerminal()) {
            log.warn("Attempted to mark completed for terminal job: {}", jobId);
            throw new ValidationException("Cannot mark completed for job in terminal state: " + job.getStatus());
        }

        job.markCompleted(generatedQuizId, job.getTotalQuestionsGenerated());
        QuizGenerationJob completedJob = jobRepository.save(job);
        log.info("Job marked as completed: {}", jobId);
        return completedJob;
    }

    @Override
    public QuizGenerationJob markJobFailed(UUID jobId, String errorMessage) {
        log.error("Marking job as failed: {}, error: {}", jobId, errorMessage);

        QuizGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz generation job not found with ID: " + jobId));

        if (job.isTerminal()) {
            log.warn("Attempted to mark failed for terminal job: {}", jobId);
            throw new ValidationException("Cannot mark failed for job in terminal state: " + job.getStatus());
        }

        job.markFailed(errorMessage);
        QuizGenerationJob failedJob = jobRepository.save(job);
        log.error("Job marked as failed: {}", jobId);
        return failedJob;
    }

    @Override
    public QuizGenerationJob cancelJob(UUID jobId, String username) {
        log.info("Cancelling job: {} for user: {}", jobId, username);

        QuizGenerationJob job = getJobByIdAndUsername(jobId, username);

        if (job.isTerminal()) {
            log.warn("Attempted to cancel terminal job: {}", jobId);
            throw new ValidationException("Cannot cancel job in terminal state: " + job.getStatus());
        }

        job.setStatus(GenerationStatus.CANCELLED);
        job.setCompletedAt(LocalDateTime.now());
        QuizGenerationJob cancelledJob = jobRepository.save(job);
        log.info("Job cancelled: {}", jobId);
        return cancelledJob;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizGenerationJob> getJobsByUser(String username, Pageable pageable) {
        log.debug("Getting jobs for user: {} with pagination", username);
        return jobRepository.findByUser_UsernameOrderByStartedAtDesc(username, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizGenerationJob> getJobsByStatus(GenerationStatus status) {
        log.debug("Getting jobs by status: {}", status);
        return jobRepository.findByStatus(status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizGenerationJob> getActiveJobs() {
        log.debug("Getting active jobs");
        return jobRepository.findByStatus(GenerationStatus.PENDING);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizGenerationJob> getJobsByDocument(UUID documentId) {
        log.debug("Getting jobs by document: {}", documentId);
        return jobRepository.findByDocumentIdAndStatus(documentId, GenerationStatus.COMPLETED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizGenerationJob> getJobsByTimeRange(LocalDateTime start, LocalDateTime end) {
        log.debug("Getting jobs by time range: {} to {}", start, end);
        return jobRepository.findByStartedAtBetween(start, end);
    }

    @Override
    @Transactional(readOnly = true)
    public JobStatistics getJobStatistics(String username) {
        log.debug("Getting job statistics for user: {}", username);

        // Simplified statistics using available repository methods
        List<QuizGenerationJob> userJobs = jobRepository.findByUser_UsernameOrderByStartedAtDesc(username);
        long totalJobs = userJobs.size();
        long completedJobs = userJobs.stream().filter(j -> j.getStatus() == GenerationStatus.COMPLETED).count();
        long failedJobs = userJobs.stream().filter(j -> j.getStatus() == GenerationStatus.FAILED).count();
        long cancelledJobs = userJobs.stream().filter(j -> j.getStatus() == GenerationStatus.CANCELLED).count();
        long activeJobs = userJobs.stream().filter(j -> !j.isTerminal()).count();

        // Calculate average generation time
        double averageGenerationTimeSeconds = userJobs.stream()
                .filter(j -> j.getGenerationTimeSeconds() != null)
                .mapToLong(QuizGenerationJob::getGenerationTimeSeconds)
                .average()
                .orElse(0.0);

        // Calculate total questions generated
        long totalQuestionsGenerated = userJobs.stream()
                .mapToLong(j -> j.getTotalQuestionsGenerated() != null ? j.getTotalQuestionsGenerated() : 0)
                .sum();

        // Get last job created
        LocalDateTime lastJobCreated = userJobs.stream()
                .map(QuizGenerationJob::getStartedAt)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        return new JobStatistics(
                totalJobs,
                completedJobs,
                failedJobs,
                cancelledJobs,
                activeJobs,
                averageGenerationTimeSeconds,
                totalQuestionsGenerated,
                lastJobCreated
        );
    }

    @Override
    public void cleanupOldJobs(int daysToKeep) {
        log.info("Cleaning up jobs older than {} days", daysToKeep);
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        jobRepository.deleteOldCompletedJobs(cutoffDate);
        log.info("Cleaned up old completed jobs");
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizGenerationJob> getStuckJobs(int maxDurationHours) {
        log.debug("Finding stuck jobs running longer than {} hours", maxDurationHours);
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(maxDurationHours);
        return jobRepository.findStuckJobs(cutoffTime);
    }
} 