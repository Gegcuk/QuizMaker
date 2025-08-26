package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.application.DocumentStructureJobService;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentStructureJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentStructureJobServiceImplTest {

    @Mock
    private DocumentStructureJobRepository jobRepository;

    @Mock
    private User user;

    private DocumentStructureJobServiceImpl jobService;

    private UUID documentId;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        jobService = new DocumentStructureJobServiceImpl(jobRepository);
        documentId = UUID.randomUUID();
        jobId = UUID.randomUUID();
    }

    // ===== CREATE JOB TESTS =====

    @Test
    void createJob_createsPendingJobWithDefaultsAndCurrentPhaseInitializing() {
        // Given
        DocumentStructureJob job = new DocumentStructureJob();
        job.setId(jobId);
        job.setDocumentId(documentId);
        job.setUser(user);
        job.setStrategy(DocumentNode.Strategy.AI);
        job.setStatus(DocumentStructureJob.StructureExtractionStatus.PENDING);
        job.setCurrentPhase("Initializing");
        job.setProgressPercentage(0.0);
        job.setNodesExtracted(0);

        when(jobRepository.existsByDocumentIdAndStatusIn(eq(documentId), any()))
            .thenReturn(false);
        when(jobRepository.save(any(DocumentStructureJob.class))).thenReturn(job);

        // When
        DocumentStructureJob result = jobService.createJob(user, documentId, DocumentNode.Strategy.AI);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(jobId);
        assertThat(result.getDocumentId()).isEqualTo(documentId);
        assertThat(result.getUser()).isEqualTo(user);
        assertThat(result.getStrategy()).isEqualTo(DocumentNode.Strategy.AI);
        assertThat(result.getStatus()).isEqualTo(DocumentStructureJob.StructureExtractionStatus.PENDING);
        assertThat(result.getCurrentPhase()).isEqualTo("Initializing");
        assertThat(result.getProgressPercentage()).isEqualTo(0.0);
        assertThat(result.getNodesExtracted()).isEqualTo(0);

        verify(jobRepository).save(any(DocumentStructureJob.class));
    }

    @Test
    void createJob_rejectsWhenHasActiveJobForDocumentIsTrue() {
        // Given
        when(jobRepository.existsByDocumentIdAndStatusIn(eq(documentId), any()))
            .thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> 
            jobService.createJob(user, documentId, DocumentNode.Strategy.AI))
            .isInstanceOf(ValidationException.class)
            .hasMessage("There is already an active structure extraction job for this document");

        verify(jobRepository, never()).save(any());
    }

    @Test
    void createJob_validatesInputs() {
        // When & Then - Null user
        assertThatThrownBy(() -> 
            jobService.createJob(null, documentId, DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("User cannot be null");

        // When & Then - Null document ID
        assertThatThrownBy(() -> 
            jobService.createJob(user, null, DocumentNode.Strategy.AI))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Document ID cannot be null");

        // When & Then - Null strategy
        assertThatThrownBy(() -> 
            jobService.createJob(user, documentId, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Strategy cannot be null");
    }

    // ===== PROGRESS/COMPLETE/FAIL/CANCEL TESTS =====

    @Test
    void updateJobProgress_flipsPendingToProcessingAndCapsZeroToHundred() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PENDING);
        job.setProgressPercentage(0.0);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocumentStructureJob.class))).thenReturn(job);

        // When
        DocumentStructureJob result = jobService.updateJobProgress(jobId, 50.0, "Processing");

        // Then
        assertThat(result.getStatus()).isEqualTo(DocumentStructureJob.StructureExtractionStatus.PROCESSING);
        assertThat(result.getProgressPercentage()).isEqualTo(50.0);
        assertThat(result.getCurrentPhase()).isEqualTo("Processing");

        verify(jobRepository).save(job);
    }

    @Test
    void updateJobProgress_capsProgressToZeroToHundred() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocumentStructureJob.class))).thenReturn(job);

        // When - Test negative value
        jobService.updateJobProgress(jobId, -10.0, "Processing");
        assertThat(job.getProgressPercentage()).isEqualTo(0.0);

        // When - Test value over 100
        jobService.updateJobProgress(jobId, 150.0, "Processing");
        assertThat(job.getProgressPercentage()).isEqualTo(100.0);
    }

    @Test
    void updateJobProgress_ignoresTerminalJobs() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.COMPLETED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        // When
        DocumentStructureJob result = jobService.updateJobProgress(jobId, 50.0, "Processing");

        // Then
        assertThat(result.getStatus()).isEqualTo(DocumentStructureJob.StructureExtractionStatus.COMPLETED);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void markJobCompleted_setsCompletedNodesExtractedSourceVersionHashProgressHundred() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING);
        job.setProgressPercentage(50.0);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocumentStructureJob.class))).thenReturn(job);

        // When
        DocumentStructureJob result = jobService.markJobCompleted(jobId, 25, "hash123");

        // Then
        assertThat(result.getStatus()).isEqualTo(DocumentStructureJob.StructureExtractionStatus.COMPLETED);
        assertThat(result.getNodesExtracted()).isEqualTo(25);
        assertThat(result.getSourceVersionHash()).isEqualTo("hash123");
        assertThat(result.getProgressPercentage()).isEqualTo(100.0);
        assertThat(result.getCurrentPhase()).isEqualTo("Completed");
        assertThat(result.getCompletedAt()).isNotNull();

        verify(jobRepository).save(job);
    }

    @Test
    void markJobFailed_setsFailedWithMessageAndCode() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocumentStructureJob.class))).thenReturn(job);

        // When
        DocumentStructureJob result = jobService.markJobFailed(jobId, "Test error", "TEST_ERROR");

        // Then
        assertThat(result.getStatus()).isEqualTo(DocumentStructureJob.StructureExtractionStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("Test error");
        assertThat(result.getErrorCode()).isEqualTo("TEST_ERROR");
        assertThat(result.getCurrentPhase()).isEqualTo("Failed");
        assertThat(result.getCompletedAt()).isNotNull();

        verify(jobRepository).save(job);
    }

    @Test
    void cancelJob_setsCancelledTerminalJobsCannotBeCancelled() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING);

        when(jobRepository.findByIdAndUser_Username(jobId, "testuser")).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocumentStructureJob.class))).thenReturn(job);

        // When
        DocumentStructureJob result = jobService.cancelJob(jobId, "testuser");

        // Then
        assertThat(result.getStatus()).isEqualTo(DocumentStructureJob.StructureExtractionStatus.CANCELLED);
        assertThat(result.getCurrentPhase()).isEqualTo("Cancelled");
        assertThat(result.getCompletedAt()).isNotNull();

        verify(jobRepository).save(job);
    }

    @Test
    void cancelJob_throwsExceptionForTerminalJobs() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.COMPLETED);

        when(jobRepository.findByIdAndUser_Username(jobId, "testuser")).thenReturn(Optional.of(job));

        // When & Then
        assertThatThrownBy(() -> 
            jobService.cancelJob(jobId, "testuser"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Cannot cancel job that is already in terminal state");

        verify(jobRepository, never()).save(any());
    }

    // ===== METRICS & QUERIES TESTS =====

    @Test
    void setExtractionMetrics_persistsValues() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.COMPLETED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(DocumentStructureJob.class))).thenReturn(job);

        // When
        jobService.setExtractionMetrics(jobId, 1000, 5, 10, 0.85);

        // Then
        assertThat(job.getCanonicalTextLength()).isEqualTo(1000);
        assertThat(job.getPreSegmentationWindows()).isEqualTo(5);
        assertThat(job.getOutlineNodesExtracted()).isEqualTo(10);
        assertThat(job.getAlignmentSuccessRate()).isEqualTo(0.85);

        verify(jobRepository).save(job);
    }

    @Test
    void cleanupOldJobs_deletesOnlyTerminalJobsOlderThanCutoff() {
        // Given
        when(jobRepository.deleteOldCompletedJobs(any(LocalDateTime.class), any())).thenReturn(5);

        // When
        int deleted = jobService.cleanupOldJobs(7);

        // Then
        assertThat(deleted).isEqualTo(5);
        verify(jobRepository).deleteOldCompletedJobs(any(LocalDateTime.class), any());
    }

    @Test
    void getStuckJobs_returnsPendingProcessingOlderThanCutoff() {
        // Given
        List<DocumentStructureJob> stuckJobs = List.of(
            createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PENDING),
            createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING)
        );

        when(jobRepository.findStuckJobs(any(LocalDateTime.class), any())).thenReturn(stuckJobs);

        // When
        List<DocumentStructureJob> result = jobService.getStuckJobs(2);

        // Then
        assertThat(result).hasSize(2);
        verify(jobRepository).findStuckJobs(any(LocalDateTime.class), any());
    }

    @Test
    void getStuckJobs_validatesInput() {
        // When & Then
        assertThatThrownBy(() -> 
            jobService.getStuckJobs(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Max duration hours must be positive");

        assertThatThrownBy(() -> 
            jobService.getStuckJobs(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Max duration hours must be positive");
    }

    // ===== ENTITY CALLBACKS TESTS =====

    @Test
    void prePersist_setsDefaults() {
        // Given
        DocumentStructureJob job = new DocumentStructureJob();
        job.setDocumentId(documentId);
        job.setUser(user);
        job.setStrategy(DocumentNode.Strategy.AI);

        // When - Simulate @PrePersist by calling the method directly using reflection
        try {
            java.lang.reflect.Method onCreateMethod = DocumentStructureJob.class.getDeclaredMethod("onCreate");
            onCreateMethod.setAccessible(true);
            onCreateMethod.invoke(job);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call onCreate method", e);
        }

        // Then
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(DocumentStructureJob.StructureExtractionStatus.PENDING);
        assertThat(job.getProgressPercentage()).isEqualTo(0.0);
        assertThat(job.getNodesExtracted()).isEqualTo(0);
    }

    @Test
    void preUpdate_computesExtractionTimeSecondsForCompletedFailedCancelled() {
        // Given
        DocumentStructureJob job = new DocumentStructureJob();
        job.setStartedAt(LocalDateTime.now().minusMinutes(5));
        job.setStatus(DocumentStructureJob.StructureExtractionStatus.COMPLETED);

        // When - Simulate @PreUpdate by calling the method directly using reflection
        try {
            java.lang.reflect.Method onUpdateMethod = DocumentStructureJob.class.getDeclaredMethod("onUpdate");
            onUpdateMethod.setAccessible(true);
            onUpdateMethod.invoke(job);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call onUpdate method", e);
        }

        // Then
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getExtractionTimeSeconds()).isNotNull();
        assertThat(job.getExtractionTimeSeconds()).isGreaterThan(0L);
    }

    @Test
    void preUpdate_doesNotComputeExtractionTimeForNonTerminalStatus() {
        // Given
        DocumentStructureJob job = new DocumentStructureJob();
        job.setStartedAt(LocalDateTime.now().minusMinutes(5));
        job.setStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING);

        // When - Simulate @PreUpdate by calling the method directly using reflection
        try {
            java.lang.reflect.Method onUpdateMethod = DocumentStructureJob.class.getDeclaredMethod("onUpdate");
            onUpdateMethod.setAccessible(true);
            onUpdateMethod.invoke(job);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call onUpdate method", e);
        }

        // Then
        assertThat(job.getCompletedAt()).isNull();
        assertThat(job.getExtractionTimeSeconds()).isNull();
    }

    // ===== GET ESTIMATED TIME REMAINING TESTS =====

    @Test
    void getEstimatedTimeRemainingSeconds_behavesSensiblyForTerminalJobs() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.COMPLETED);

        // When
        Long result = job.getEstimatedTimeRemainingSeconds();

        // Then
        assertThat(result).isEqualTo(0L);
    }

    @Test
    void getEstimatedTimeRemainingSeconds_behavesSensiblyForZeroProgress() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PENDING);
        job.setProgressPercentage(0.0);

        // When
        Long result = job.getEstimatedTimeRemainingSeconds();

        // Then
        assertThat(result).isEqualTo(0L);
    }

    @Test
    void getEstimatedTimeRemainingSeconds_calculatesCorrectlyForActiveJobs() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PROCESSING);
        job.setStartedAt(LocalDateTime.now().minusMinutes(5));
        job.setProgressPercentage(50.0);

        // When
        Long result = job.getEstimatedTimeRemainingSeconds();

        // Then
        assertThat(result).isGreaterThan(0L);
        // Should be approximately 5 minutes (300 seconds) since 50% progress in 5 minutes
        assertThat(result).isCloseTo(300L, org.assertj.core.data.Offset.offset(60L));
    }

    @Test
    void getEstimatedTimeRemainingSeconds_handlesNullProgress() {
        // Given
        DocumentStructureJob job = createJobWithStatus(DocumentStructureJob.StructureExtractionStatus.PENDING);
        job.setProgressPercentage(null);

        // When
        Long result = job.getEstimatedTimeRemainingSeconds();

        // Then
        assertThat(result).isEqualTo(0L);
    }

    // ===== HELPER METHODS =====

    private DocumentStructureJob createJobWithStatus(DocumentStructureJob.StructureExtractionStatus status) {
        DocumentStructureJob job = new DocumentStructureJob();
        job.setId(jobId);
        job.setDocumentId(documentId);
        job.setUser(user);
        job.setStrategy(DocumentNode.Strategy.AI);
        job.setStatus(status);
        job.setStartedAt(LocalDateTime.now().minusMinutes(5));
        job.setProgressPercentage(0.0);
        job.setNodesExtracted(0);
        return job;
    }
}
