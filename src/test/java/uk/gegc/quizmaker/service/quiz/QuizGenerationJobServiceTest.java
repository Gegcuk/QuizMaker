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
        when(jobRepository.findByStatus(GenerationStatus.PENDING)).thenReturn(jobs);

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
} 