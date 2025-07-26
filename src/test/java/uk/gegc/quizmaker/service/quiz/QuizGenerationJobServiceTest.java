package uk.gegc.quizmaker.service.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.quiz.GenerationStatus;
import uk.gegc.quizmaker.model.quiz.QuizGenerationJob;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizGenerationJobRepository;
import uk.gegc.quizmaker.service.quiz.impl.QuizGenerationJobServiceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuizGenerationJobServiceTest {

    @Mock
    private QuizGenerationJobRepository jobRepository;

    @InjectMocks
    private QuizGenerationJobServiceImpl jobService;

    private User testUser;
    private UUID testDocumentId;
    private UUID testJobId;
    private QuizGenerationJob testJob;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUsername("testuser");

        testDocumentId = UUID.randomUUID();
        testJobId = UUID.randomUUID();

        testJob = new QuizGenerationJob();
        testJob.setId(testJobId);
        testJob.setUser(testUser);
        testJob.setDocumentId(testDocumentId);
        testJob.setStatus(GenerationStatus.PENDING);
        testJob.setStartedAt(LocalDateTime.now());
        testJob.setTotalChunks(5);
        testJob.setProcessedChunks(0);
        testJob.setTotalQuestionsGenerated(0);
    }

    @Test
    void shouldCreateJobSuccessfully() {
        // Given
        String requestData = "{\"documentId\":\"" + testDocumentId + "\"}";
        int totalChunks = 5;
        int estimatedTimeSeconds = 300;

        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When
        QuizGenerationJob createdJob = jobService.createJob(testUser, testDocumentId,
                requestData, totalChunks, estimatedTimeSeconds);

        // Then
        assertNotNull(createdJob);
        assertEquals(testJobId, createdJob.getId());
        assertEquals(testUser, createdJob.getUser());
        assertEquals(testDocumentId, createdJob.getDocumentId());
        assertEquals(GenerationStatus.PENDING, createdJob.getStatus());
        assertEquals(totalChunks, createdJob.getTotalChunks());
        assertEquals(0, createdJob.getProcessedChunks());
        assertEquals(0, createdJob.getTotalQuestionsGenerated());

        verify(jobRepository).save(any(QuizGenerationJob.class));
    }

    @Test
    void shouldGetJobByIdAndUsernameSuccessfully() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));

        // When
        QuizGenerationJob foundJob = jobService.getJobByIdAndUsername(testJobId, "testuser");

        // Then
        assertNotNull(foundJob);
        assertEquals(testJobId, foundJob.getId());
        assertEquals("testuser", foundJob.getUser().getUsername());
    }

    @Test
    void shouldThrowResourceNotFoundExceptionWhenJobNotFound() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> {
            jobService.getJobByIdAndUsername(testJobId, "testuser");
        });
    }

    @Test
    void shouldThrowValidationExceptionWhenUserNotAuthorized() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));

        // When & Then
        assertThrows(ValidationException.class, () -> {
            jobService.getJobByIdAndUsername(testJobId, "unauthorizeduser");
        });
    }

    @Test
    void shouldGetJobByIdSuccessfully() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));

        // When
        Optional<QuizGenerationJob> foundJob = jobService.getJobById(testJobId);

        // Then
        assertTrue(foundJob.isPresent());
        assertEquals(testJobId, foundJob.get().getId());
    }

    @Test
    void shouldReturnEmptyWhenJobNotFound() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(Optional.empty());

        // When
        Optional<QuizGenerationJob> foundJob = jobService.getJobById(testJobId);

        // Then
        assertFalse(foundJob.isPresent());
    }

    @Test
    void shouldUpdateJobProgressSuccessfully() {
        // Given
        testJob.setStatus(GenerationStatus.PROCESSING);
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When
        QuizGenerationJob updatedJob = jobService.updateJobProgress(testJobId, 2, 3, 10);

        // Then
        assertNotNull(updatedJob);
        assertEquals(GenerationStatus.PROCESSING, updatedJob.getStatus());
        assertEquals(2, updatedJob.getProcessedChunks());
        assertEquals("3", updatedJob.getCurrentChunk());
        assertEquals(10, updatedJob.getTotalQuestionsGenerated());

        verify(jobRepository).save(testJob);
    }

    @Test
    void shouldThrowValidationExceptionWhenUpdatingTerminalJob() {
        // Given
        testJob.setStatus(GenerationStatus.COMPLETED);
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));

        // When & Then
        assertThrows(ValidationException.class, () -> {
            jobService.updateJobProgress(testJobId, 2, 3, 10);
        });

        verify(jobRepository, never()).save(any());
    }

    @Test
    void shouldMarkJobCompletedSuccessfully() {
        // Given
        UUID generatedQuizId = UUID.randomUUID();
        testJob.setStatus(GenerationStatus.PROCESSING);
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When
        QuizGenerationJob completedJob = jobService.markJobCompleted(testJobId, generatedQuizId);

        // Then
        assertNotNull(completedJob);
        assertEquals(GenerationStatus.COMPLETED, completedJob.getStatus());
        assertEquals(generatedQuizId, completedJob.getGeneratedQuizId());
        assertNotNull(completedJob.getCompletedAt());

        verify(jobRepository).save(testJob);
    }

    @Test
    void shouldMarkJobFailedSuccessfully() {
        // Given
        String errorMessage = "AI service unavailable";
        testJob.setStatus(GenerationStatus.PROCESSING);
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When
        QuizGenerationJob failedJob = jobService.markJobFailed(testJobId, errorMessage);

        // Then
        assertNotNull(failedJob);
        assertEquals(GenerationStatus.FAILED, failedJob.getStatus());
        assertEquals(errorMessage, failedJob.getErrorMessage());
        assertNotNull(failedJob.getCompletedAt());

        verify(jobRepository).save(testJob);
    }

    @Test
    void shouldCancelJobSuccessfully() {
        // Given
        testJob.setStatus(GenerationStatus.PROCESSING);
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When
        QuizGenerationJob cancelledJob = jobService.cancelJob(testJobId, "testuser");

        // Then
        assertNotNull(cancelledJob);
        assertEquals(GenerationStatus.CANCELLED, cancelledJob.getStatus());
        assertNotNull(cancelledJob.getCompletedAt());

        verify(jobRepository).save(testJob);
    }

    @Test
    void shouldThrowValidationExceptionWhenCancellingTerminalJob() {
        // Given
        testJob.setStatus(GenerationStatus.COMPLETED);
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));

        // When & Then
        assertThrows(ValidationException.class, () -> {
            jobService.cancelJob(testJobId, "testuser");
        });

        verify(jobRepository, never()).save(any());
    }

    @Test
    void shouldGetJobsByUserWithPagination() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        List<QuizGenerationJob> jobs = List.of(testJob);
        Page<QuizGenerationJob> jobPage = new PageImpl<>(jobs, pageable, 1);

        when(jobRepository.findByUser_UsernameOrderByStartedAtDesc("testuser", pageable)).thenReturn(jobPage);

        // When
        Page<QuizGenerationJob> result = jobService.getJobsByUser("testuser", pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(testJob, result.getContent().get(0));
    }

    @Test
    void shouldGetJobsByStatus() {
        // Given
        List<QuizGenerationJob> jobs = List.of(testJob);
        when(jobRepository.findByStatus(GenerationStatus.PENDING)).thenReturn(jobs);

        // When
        List<QuizGenerationJob> result = jobService.getJobsByStatus(GenerationStatus.PENDING);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testJob, result.get(0));
    }

    @Test
    void shouldGetActiveJobs() {
        // Given
        List<QuizGenerationJob> jobs = List.of(testJob);
        when(jobRepository.findByStatusIn(List.of(GenerationStatus.PENDING, GenerationStatus.PROCESSING))).thenReturn(jobs);
        when(jobRepository.findByStatus(GenerationStatus.PENDING)).thenReturn(jobs);
        when(jobRepository.findByStatusAndStartedAtBefore(eq(GenerationStatus.PENDING), any(LocalDateTime.class))).thenReturn(List.of());

        // When
        List<QuizGenerationJob> result = jobService.getActiveJobs();

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testJob, result.get(0));
    }

    @Test
    void shouldGetJobsByDocument() {
        // Given
        List<QuizGenerationJob> jobs = List.of(testJob);
        when(jobRepository.findByDocumentIdAndStatus(testDocumentId, GenerationStatus.COMPLETED)).thenReturn(jobs);

        // When
        List<QuizGenerationJob> result = jobService.getJobsByDocument(testDocumentId);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testJob, result.get(0));
    }

    @Test
    void shouldGetJobsByTimeRange() {
        // Given
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        List<QuizGenerationJob> jobs = List.of(testJob);

        when(jobRepository.findByStartedAtBetween(start, end)).thenReturn(jobs);

        // When
        List<QuizGenerationJob> result = jobService.getJobsByTimeRange(start, end);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testJob, result.get(0));
    }

    @Test
    void shouldGetJobStatistics() {
        // Given
        List<QuizGenerationJob> userJobs = List.of(testJob);
        when(jobRepository.findByUser_UsernameOrderByStartedAtDesc("testuser")).thenReturn(userJobs);

        // When
        QuizGenerationJobService.JobStatistics statistics = jobService.getJobStatistics("testuser");

        // Then
        assertNotNull(statistics);
        assertEquals(1, statistics.totalJobs());
        assertEquals(1, statistics.activeJobs());
        assertEquals(0, statistics.completedJobs());
        assertEquals(0, statistics.failedJobs());
        assertEquals(0, statistics.cancelledJobs());
    }

    @Test
    void shouldCleanupOldJobs() {
        // Given
        int daysToKeep = 30;

        // When
        jobService.cleanupOldJobs(daysToKeep);

        // Then
        // Verify that cleanup logic is called (implementation dependent)
        // This test ensures the method doesn't throw exceptions
    }

    @Test
    void shouldGetStuckJobs() {
        // Given
        int maxDurationHours = 2;
        List<QuizGenerationJob> stuckJobs = List.of(testJob);
        when(jobRepository.findStuckJobs(any(LocalDateTime.class))).thenReturn(stuckJobs);

        // When
        List<QuizGenerationJob> result = jobService.getStuckJobs(maxDurationHours);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(testJob, result.get(0));
        verify(jobRepository).findStuckJobs(any(LocalDateTime.class));
    }

    @Test
    void shouldHandleJobCreationWithNullUser() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.createJob(null, testDocumentId, "requestData", 5, 300);
        });
    }

    @Test
    void shouldHandleJobCreationWithNullDocumentId() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.createJob(testUser, null, "requestData", 5, 300);
        });
    }

    @Test
    void shouldHandleJobCreationWithNullRequestData() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.createJob(testUser, testDocumentId, null, 5, 300);
        });
    }

    @Test
    void shouldHandleJobCreationWithNegativeTotalChunks() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.createJob(testUser, testDocumentId, "requestData", -1, 300);
        });
    }

    @Test
    void shouldHandleJobCreationWithNegativeEstimatedTime() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.createJob(testUser, testDocumentId, "requestData", 5, -1);
        });
    }

    @Test
    void shouldHandleJobCreationWithZeroTotalChunks() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.createJob(testUser, testDocumentId, "requestData", 0, 300);
        });
    }

    @Test
    void shouldHandleJobCreationWithZeroEstimatedTime() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.createJob(testUser, testDocumentId, "requestData", 5, 0);
        });
    }

    @Test
    void shouldHandleUpdateJobProgressWithNegativeProcessedChunks() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.updateJobProgress(testJobId, -1, 1, 5);
        });
    }

    @Test
    void shouldHandleUpdateJobProgressWithNegativeCurrentChunk() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.updateJobProgress(testJobId, 1, -1, 5);
        });
    }

    @Test
    void shouldHandleUpdateJobProgressWithNegativeTotalQuestions() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.updateJobProgress(testJobId, 1, 1, -1);
        });
    }

    @Test
    void shouldHandleMarkJobCompletedWithNullGeneratedQuizId() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.markJobCompleted(testJobId, null);
        });
    }

    @Test
    void shouldHandleMarkJobFailedWithNullErrorMessage() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.markJobFailed(testJobId, null);
        });
    }

    @Test
    void shouldHandleMarkJobFailedWithEmptyErrorMessage() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.markJobFailed(testJobId, "");
        });
    }

    @Test
    void shouldHandleCancelJobWithNullUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.cancelJob(testJobId, null);
        });
    }

    @Test
    void shouldHandleCancelJobWithEmptyUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.cancelJob(testJobId, "");
        });
    }

    @Test
    void shouldHandleGetJobsByUserWithNullUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByUser(null, PageRequest.of(0, 10));
        });
    }

    @Test
    void shouldHandleGetJobsByUserWithEmptyUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByUser("", PageRequest.of(0, 10));
        });
    }

    @Test
    void shouldHandleGetJobsByUserWithNullPageable() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByUser("testuser", null);
        });
    }

    @Test
    void shouldHandleGetJobsByStatusWithNullStatus() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByStatus(null);
        });
    }

    @Test
    void shouldHandleGetJobsByDocumentWithNullDocumentId() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByDocument(null);
        });
    }

    @Test
    void shouldHandleGetJobsByTimeRangeWithNullStart() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByTimeRange(null, LocalDateTime.now());
        });
    }

    @Test
    void shouldHandleGetJobsByTimeRangeWithNullEnd() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByTimeRange(LocalDateTime.now(), null);
        });
    }

    @Test
    void shouldHandleGetJobsByTimeRangeWithInvalidRange() {
        // Given
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.minusHours(1); // End before start

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobsByTimeRange(start, end);
        });
    }

    @Test
    void shouldHandleGetJobStatisticsWithNullUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobStatistics(null);
        });
    }

    @Test
    void shouldHandleGetJobStatisticsWithEmptyUsername() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getJobStatistics("");
        });
    }

    @Test
    void shouldHandleCleanupOldJobsWithNegativeDays() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.cleanupOldJobs(-1);
        });
    }

    @Test
    void shouldHandleCleanupOldJobsWithZeroDays() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.cleanupOldJobs(0);
        });
    }

    @Test
    void shouldHandleGetStuckJobsWithNegativeHours() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getStuckJobs(-1);
        });
    }

    @Test
    void shouldHandleGetStuckJobsWithZeroHours() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            jobService.getStuckJobs(0);
        });
    }

    @Test
    void shouldHandleConcurrentJobUpdates() {
        // Given
        when(jobRepository.findById(testJobId)).thenReturn(Optional.of(testJob));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(testJob);

        // When - simulate concurrent updates
        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
            jobService.updateJobProgress(testJobId, 1, 1, 5);
        });
        
        CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
            jobService.updateJobProgress(testJobId, 2, 2, 10);
        });

        // Then - should complete without exceptions
        assertDoesNotThrow(() -> {
            CompletableFuture.allOf(future1, future2).join();
        });
    }

    @Test
    void shouldHandleJobStatisticsWithNoJobs() {
        // Given
        when(jobRepository.findByUser_UsernameOrderByStartedAtDesc("testuser")).thenReturn(List.of());

        // When
        QuizGenerationJobService.JobStatistics statistics = jobService.getJobStatistics("testuser");

        // Then
        assertNotNull(statistics);
        assertEquals(0, statistics.totalJobs());
        assertEquals(0, statistics.activeJobs());
        assertEquals(0, statistics.completedJobs());
        assertEquals(0, statistics.failedJobs());
        assertEquals(0, statistics.cancelledJobs());
        assertEquals(0.0, statistics.averageGenerationTimeSeconds());
        assertEquals(0, statistics.totalQuestionsGenerated());
        assertNull(statistics.lastJobCreated());
    }

    @Test
    void shouldHandleJobStatisticsWithNullValues() {
        // Given
        testJob.setGenerationTimeSeconds(null);
        testJob.setTotalQuestionsGenerated(null);
        testJob.setStartedAt(null);
        when(jobRepository.findByUser_UsernameOrderByStartedAtDesc("testuser")).thenReturn(List.of(testJob));

        // When
        QuizGenerationJobService.JobStatistics statistics = jobService.getJobStatistics("testuser");

        // Then
        assertNotNull(statistics);
        assertEquals(1, statistics.totalJobs());
        assertEquals(0.0, statistics.averageGenerationTimeSeconds());
        assertEquals(0, statistics.totalQuestionsGenerated());
        assertNull(statistics.lastJobCreated());
    }
} 