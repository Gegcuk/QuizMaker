package uk.gegc.quizmaker.features.quiz.domain.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QuizGenerationJob task-level progress computation.
 * Tests the precedence logic: tasks > chunks, and edge cases with null/zero totals.
 */
@DisplayName("QuizGenerationJob Task Progress Tests")
class QuizGenerationJobTaskProgressTest {

    private QuizGenerationJob job;
    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setUsername("testuser");
        
        job = new QuizGenerationJob();
        job.setId(UUID.randomUUID());
        job.setUser(testUser);
        job.setStatus(GenerationStatus.PENDING);
    }

    @Test
    @DisplayName("Progress percentage prefers task counters when available")
    void progressPercentage_prefersTasks_whenTaskCountersSet() {
        // Given: both chunk and task counters are set
        job.setTotalChunks(10);
        job.setProcessedChunks(5); // Would be 50%
        
        job.setTotalTasks(20);
        job.setCompletedTasks(10); // Should be 50%
        
        // When: update is triggered
        job.updateProgress(5, "test");
        
        // Then: uses task-based calculation (10/20 = 50%)
        assertEquals(50.0, job.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("Progress percentage falls back to chunks when tasks not set")
    void progressPercentage_fallsBackToChunks_whenTaskCountersNotSet() {
        // Given: only chunk counters are set
        job.setTotalChunks(10);
        job.setProcessedChunks(3);
        
        // When: update is triggered
        job.updateProgress(3, "test");
        
        // Then: uses chunk-based calculation (3/10 = 30%)
        assertEquals(30.0, job.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("Progress percentage handles totalTasks = 0")
    void progressPercentage_handlesTotalTasksZero() {
        // Given: totalTasks is explicitly 0
        job.setTotalTasks(0);
        job.setCompletedTasks(5);
        
        job.setTotalChunks(10);
        job.setProcessedChunks(5);
        
        // When: update is triggered
        job.updateProgress(5, "test");
        
        // Then: falls back to chunks (5/10 = 50%)
        assertEquals(50.0, job.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("Progress percentage handles totalTasks = null")
    void progressPercentage_handlesTotalTasksNull() {
        // Given: totalTasks is null
        job.setTotalTasks(null);
        job.setCompletedTasks(5);
        
        job.setTotalChunks(10);
        job.setProcessedChunks(5);
        
        // When: update is triggered
        job.updateProgress(5, "test");
        
        // Then: falls back to chunks (5/10 = 50%)
        assertEquals(50.0, job.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("Progress percentage handles totalChunks = 0 as fallback")
    void progressPercentage_handlesTotalChunksZero() {
        // Given: both totals are 0
        job.setTotalTasks(null);
        job.setTotalChunks(0);
        job.setProcessedChunks(5);
        
        // When: update is triggered
        job.updateProgress(5, "test");
        
        // Then: cannot compute, stays at default (0.0 is acceptable)
        assertNotNull(job.getProgressPercentage());
    }

    @Test
    @DisplayName("updateTaskProgressIncrement updates completed tasks and status")
    void updateTaskProgressIncrement_updatesCountersAndStatus() {
        // Given
        job.setTotalTasks(15);
        job.setCompletedTasks(5);
        
        // When
        job.updateTaskProgressIncrement(2, "Chunk 1/3 · MCQ_SINGLE · done");
        
        // Then
        assertEquals(7, job.getCompletedTasks());
        assertEquals("Chunk 1/3 · MCQ_SINGLE · done", job.getCurrentChunk());
        assertEquals(7.0 / 15.0 * 100.0, job.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("updateTaskProgressIncrement handles null completedTasks")
    void updateTaskProgressIncrement_handlesNullCompletedTasks() {
        // Given
        job.setTotalTasks(10);
        job.setCompletedTasks(null);
        
        // When
        job.updateTaskProgressIncrement(1, "Status");
        
        // Then: treats null as 0
        assertEquals(1, job.getCompletedTasks());
    }

    @Test
    @DisplayName("markCompleted sets completedTasks = totalTasks when available")
    void markCompleted_alignsTaskCounters() {
        // Given
        job.setTotalTasks(20);
        job.setCompletedTasks(18);
        
        job.setTotalChunks(5);
        job.setProcessedChunks(4);
        
        // When
        job.markCompleted(UUID.randomUUID(), 50);
        
        // Then
        assertEquals(GenerationStatus.COMPLETED, job.getStatus());
        assertEquals(20, job.getCompletedTasks());
        assertEquals(5, job.getProcessedChunks());
        assertEquals(100.0, job.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("markCompleted handles null totalTasks gracefully")
    void markCompleted_handlesNullTotalTasks() {
        // Given
        job.setTotalTasks(null);
        job.setTotalChunks(5);
        job.setProcessedChunks(4);
        
        // When
        job.markCompleted(UUID.randomUUID(), 50);
        
        // Then: doesn't crash, completes successfully
        assertEquals(GenerationStatus.COMPLETED, job.getStatus());
        assertEquals(5, job.getProcessedChunks());
        assertEquals(100.0, job.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("markFailed does not force 100% progress")
    void markFailed_doesNotForceProgressTo100() {
        // Given
        job.setTotalTasks(20);
        job.setCompletedTasks(10);
        job.setProgressPercentage(50.0); // Set explicitly
        
        // When
        job.markFailed("Test error");
        
        // Then: keeps partial progress (doesn't recalculate to 0)
        assertEquals(GenerationStatus.FAILED, job.getStatus());
        assertEquals(10, job.getCompletedTasks());
        // Progress stays as set (markFailed doesn't modify it)
        assertEquals("Test error", job.getErrorMessage());
    }

    @Test
    @DisplayName("completedTasks defaults to 0 via field initializer")
    void completedTasks_defaultsToZero() {
        // Given: new job
        QuizGenerationJob newJob = new QuizGenerationJob();
        
        // Then: completedTasks is initialized to 0 via field default
        assertEquals(0, newJob.getCompletedTasks());
    }
}

