package uk.gegc.quizmaker.features.quiz.application.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;

import static org.mockito.Mockito.*;

/**
 * Unit tests for QuizGenerationJobCleanupScheduler
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizGenerationJobCleanupScheduler Tests")
class QuizGenerationJobCleanupSchedulerTest {

    @Mock
    private QuizGenerationJobService jobService;

    @InjectMocks
    private QuizGenerationJobCleanupScheduler scheduler;

    @Test
    @DisplayName("cleanupStalePendingJobs: when called then invokes job service cleanup")
    void cleanupStalePendingJobs_whenCalled_thenInvokesJobServiceCleanup() {
        // Given
        doNothing().when(jobService).cleanupStalePendingJobs();

        // When
        scheduler.cleanupStalePendingJobs();

        // Then
        verify(jobService, times(1)).cleanupStalePendingJobs();
    }

    @Test
    @DisplayName("cleanupStalePendingJobs: when service throws exception then does not propagate")
    void cleanupStalePendingJobs_whenServiceThrowsException_thenDoesNotPropagate() {
        // Given
        doThrow(new RuntimeException("Cleanup failed")).when(jobService).cleanupStalePendingJobs();

        // When & Then - should not throw exception
        scheduler.cleanupStalePendingJobs();
        
        // Verify cleanup was attempted
        verify(jobService, times(1)).cleanupStalePendingJobs();
    }
}

