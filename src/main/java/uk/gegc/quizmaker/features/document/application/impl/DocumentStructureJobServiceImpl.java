package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.application.DocumentStructureJobService;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentStructureJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of DocumentStructureJobService.
 * <p>
 * Manages document structure extraction jobs with proper transaction boundaries
 * and security checks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentStructureJobServiceImpl implements DocumentStructureJobService {

    private final DocumentStructureJobRepository jobRepository;

    @Override
    @Transactional
    public DocumentStructureJob createJob(User user, UUID documentId, DocumentNode.Strategy strategy) {
        // Validate input
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy cannot be null");
        }

        // Check if there's already an active job for this document
        if (hasActiveJobForDocument(documentId)) {
            throw new ValidationException("There is already an active structure extraction job for this document");
        }

        log.info("Creating document structure extraction job for document {} with strategy {} for user {}", 
                documentId, strategy, user.getUsername());

        DocumentStructureJob job = new DocumentStructureJob();
        job.setDocumentId(documentId);
        job.setUser(user);
        job.setStrategy(strategy);
        job.setStatus(DocumentStructureJob.StructureExtractionStatus.PENDING);
        job.setCurrentPhase("Initializing");

        DocumentStructureJob savedJob = jobRepository.save(job);
        
        log.info("Created document structure extraction job {} for document {}", 
                savedJob.getId(), documentId);
        
        return savedJob;
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentStructureJob getJobByIdAndUsername(UUID jobId, String username) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }

        return jobRepository.findByIdAndUser_Username(jobId, username)
                .orElseThrow(() -> new ResourceNotFoundException("Structure extraction job not found: " + jobId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentStructureJob> getJobById(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }
        return jobRepository.findById(jobId);
    }

    @Override
    @Transactional
    public DocumentStructureJob updateJobProgress(UUID jobId, double progressPercentage, String currentPhase) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }

        DocumentStructureJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Structure extraction job not found: " + jobId));

        if (job.isTerminal()) {
            log.warn("Attempted to update progress for terminal job {}", jobId);
            return job;
        }

        job.updateProgress(progressPercentage, currentPhase);
        
        // Set status to PROCESSING if it was PENDING
        if (job.getStatus() == DocumentStructureJob.StructureExtractionStatus.PENDING) {
            job.setStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING);
        }

        DocumentStructureJob savedJob = jobRepository.save(job);
        
        log.debug("Updated progress for job {} to {}% - {}", jobId, progressPercentage, currentPhase);
        
        return savedJob;
    }

    @Override
    @Transactional
    public DocumentStructureJob markJobCompleted(UUID jobId, int nodesExtracted, String sourceVersionHash) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }

        DocumentStructureJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Structure extraction job not found: " + jobId));

        job.markCompleted(nodesExtracted, sourceVersionHash);
        DocumentStructureJob savedJob = jobRepository.save(job);
        
        log.info("Marked job {} as completed with {} nodes extracted", jobId, nodesExtracted);
        
        return savedJob;
    }

    @Override
    @Transactional
    public DocumentStructureJob markJobFailed(UUID jobId, String errorMessage, String errorCode) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }

        DocumentStructureJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Structure extraction job not found: " + jobId));

        job.markFailed(errorMessage, errorCode);
        DocumentStructureJob savedJob = jobRepository.save(job);
        
        log.error("Marked job {} as failed: {} (code: {})", jobId, errorMessage, errorCode);
        
        return savedJob;
    }

    @Override
    @Transactional
    public DocumentStructureJob cancelJob(UUID jobId, String username) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }

        DocumentStructureJob job = getJobByIdAndUsername(jobId, username);
        
        if (job.isTerminal()) {
            throw new ValidationException("Cannot cancel job that is already in terminal state: " + job.getStatus());
        }

        job.markCancelled();
        DocumentStructureJob savedJob = jobRepository.save(job);
        
        log.info("Cancelled job {} by user {}", jobId, username);
        
        return savedJob;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DocumentStructureJob> getJobsByUser(String username, Pageable pageable) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
        return jobRepository.findByUser_UsernameOrderByStartedAtDesc(username, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentStructureJob> getJobsByStatus(DocumentStructureJob.StructureExtractionStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Status cannot be null");
        }
        return jobRepository.findByStatusOrderByStartedAtAsc(status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentStructureJob> getActiveJobs() {
        return jobRepository.findByStatusInOrderByStartedAtAsc(
            List.of(DocumentStructureJob.StructureExtractionStatus.PENDING, 
                   DocumentStructureJob.StructureExtractionStatus.PROCESSING));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentStructureJob> getJobsByDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        return jobRepository.findByDocumentIdOrderByStartedAtDesc(documentId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentStructureJob> getJobsByTimeRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Start and end times cannot be null");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Start time must be before end time");
        }
        return jobRepository.findByStartedAtBetweenOrderByStartedAtDesc(start, end);
    }

    @Override
    @Transactional(readOnly = true)
    public JobStatistics getJobStatistics(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
        
        long totalJobs = jobRepository.countByUser_Username(username);
        long completedJobs = jobRepository.countByUser_UsernameAndStatus(username, DocumentStructureJob.StructureExtractionStatus.COMPLETED);
        long failedJobs = jobRepository.countByUser_UsernameAndStatus(username, DocumentStructureJob.StructureExtractionStatus.FAILED);
        long cancelledJobs = jobRepository.countByUser_UsernameAndStatus(username, DocumentStructureJob.StructureExtractionStatus.CANCELLED);
        long activeJobs = jobRepository.countByUser_UsernameAndStatus(username, DocumentStructureJob.StructureExtractionStatus.PENDING) +
                         jobRepository.countByUser_UsernameAndStatus(username, DocumentStructureJob.StructureExtractionStatus.PROCESSING);
        
        Double avgTime = jobRepository.getAverageExtractionTimeByUsername(username);
        double averageExtractionTimeSeconds = avgTime != null ? avgTime : 0.0;
        
        Long totalNodes = jobRepository.getTotalNodesExtractedByUsername(username);
        long totalNodesExtracted = totalNodes != null ? totalNodes : 0L;
        
        LocalDateTime lastJobCreated = jobRepository.getLastJobCreatedByUsername(username).orElse(null);
        
        return new JobStatistics(
                totalJobs, completedJobs, failedJobs, cancelledJobs, activeJobs,
                averageExtractionTimeSeconds, totalNodesExtracted, lastJobCreated
        );
    }

    @Override
    @Transactional
    public int cleanupOldJobs(int daysToKeep) {
        if (daysToKeep < 0) {
            throw new IllegalArgumentException("Days to keep must be non-negative");
        }
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        Collection<DocumentStructureJob.StructureExtractionStatus> terminalStatuses = 
            List.of(DocumentStructureJob.StructureExtractionStatus.COMPLETED, 
                   DocumentStructureJob.StructureExtractionStatus.FAILED, 
                   DocumentStructureJob.StructureExtractionStatus.CANCELLED);
        int deleted = jobRepository.deleteOldCompletedJobs(cutoffDate, terminalStatuses);
        
        log.info("Cleaned up {} old structure extraction jobs older than {} days", deleted, daysToKeep);
        
        return deleted;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentStructureJob> getStuckJobs(int maxDurationHours) {
        if (maxDurationHours <= 0) {
            throw new IllegalArgumentException("Max duration hours must be positive");
        }
        
        LocalDateTime cutoffTime = LocalDateTime.now().minusHours(maxDurationHours);
        Collection<DocumentStructureJob.StructureExtractionStatus> activeStatuses = 
            List.of(DocumentStructureJob.StructureExtractionStatus.PENDING, 
                   DocumentStructureJob.StructureExtractionStatus.PROCESSING);
        return jobRepository.findStuckJobs(cutoffTime, activeStatuses);
    }

    @Override
    @Transactional
    public int cleanupStalePendingJobs() {
        // Consider jobs stuck if they've been pending for more than 1 hour
        List<DocumentStructureJob> stuckJobs = getStuckJobs(1);
        
        int cleaned = 0;
        for (DocumentStructureJob job : stuckJobs) {
            if (job.getStatus() == DocumentStructureJob.StructureExtractionStatus.PENDING) {
                job.markFailed("Job timed out", "TIMEOUT");
                jobRepository.save(job);
                cleaned++;
            }
        }
        
        if (cleaned > 0) {
            log.info("Cleaned up {} stale pending structure extraction jobs", cleaned);
        }
        
        return cleaned;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveJobForDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        return jobRepository.existsByDocumentIdAndStatusIn(documentId, 
            List.of(DocumentStructureJob.StructureExtractionStatus.PENDING, 
                   DocumentStructureJob.StructureExtractionStatus.PROCESSING));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentStructureJob> getMostRecentCompletedJobForDocument(UUID documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        return jobRepository.findFirstByDocumentIdAndStatusOrderByCompletedAtDesc(documentId, 
            DocumentStructureJob.StructureExtractionStatus.COMPLETED);
    }

    @Override
    @Transactional
    public void setExtractionMetrics(UUID jobId, int canonicalTextLength, int preSegmentationWindows,
                                   int outlineNodesExtracted, double alignmentSuccessRate) {
        if (jobId == null) {
            throw new IllegalArgumentException("Job ID cannot be null");
        }

        DocumentStructureJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Structure extraction job not found: " + jobId));

        job.setExtractionMetrics(canonicalTextLength, preSegmentationWindows, 
                               outlineNodesExtracted, alignmentSuccessRate);
        
        jobRepository.save(job);
        
        log.debug("Set extraction metrics for job {}: text={}, windows={}, nodes={}, alignment={}%", 
                jobId, canonicalTextLength, preSegmentationWindows, outlineNodesExtracted, 
                alignmentSuccessRate * 100);
    }
}
