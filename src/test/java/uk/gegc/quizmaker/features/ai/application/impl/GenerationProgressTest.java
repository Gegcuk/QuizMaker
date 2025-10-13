package uk.gegc.quizmaker.features.ai.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GenerationProgress inner class.
 * Tests that progress percentage prefers task counters over chunk counters.
 */
@DisplayName("GenerationProgress Task Counter Tests")
class GenerationProgressTest {

    private AiQuizGenerationServiceImpl.GenerationProgress progress;

    @BeforeEach
    void setUp() {
        progress = new AiQuizGenerationServiceImpl.GenerationProgress();
    }

    @Test
    @DisplayName("Progress percentage uses task counters when totalTasks > 0")
    void progressPercentage_usesTasks_whenTotalTasksSet() {
        // Given
        progress.setTotalChunks(10);
        progress.setTotalTasks(30);
        
        // Simulate 15 out of 30 tasks completed (50%)
        for (int i = 0; i < 15; i++) {
            progress.incrementCompletedTasks();
        }
        
        // When
        double percentage = progress.getProgressPercentage();
        
        // Then: uses tasks (15/30 = 50%), not chunks (0/10 = 0%)
        assertEquals(50.0, percentage, 0.01);
    }

    @Test
    @DisplayName("Progress percentage falls back to chunks when totalTasks = 0")
    void progressPercentage_fallsBackToChunks_whenTotalTasksZero() {
        // Given
        progress.setTotalChunks(10);
        progress.setTotalTasks(0);
        
        // Simulate 5 chunks processed
        for (int i = 0; i < 5; i++) {
            progress.incrementProcessedChunks();
        }
        
        // When
        double percentage = progress.getProgressPercentage();
        
        // Then: uses chunks (5/10 = 50%)
        assertEquals(50.0, percentage, 0.01);
    }

    @Test
    @DisplayName("Progress percentage handles totalChunks = 0")
    void progressPercentage_handlesTotalChunksZero() {
        // Given
        progress.setTotalChunks(0);
        progress.setTotalTasks(0);
        
        // When
        double percentage = progress.getProgressPercentage();
        
        // Then: returns 0
        assertEquals(0.0, percentage, 0.01);
    }

    @Test
    @DisplayName("incrementCompletedTasks is thread-safe")
    void incrementCompletedTasks_isThreadSafe() throws InterruptedException {
        // Given
        progress.setTotalTasks(1000);
        
        // When: increment from multiple threads
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    progress.incrementCompletedTasks();
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Then: all increments are counted
        assertEquals(1000, progress.getCompletedTasks());
        assertEquals(100.0, progress.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("incrementProcessedChunks is thread-safe")
    void incrementProcessedChunks_isThreadSafe() throws InterruptedException {
        // Given
        progress.setTotalChunks(500);
        progress.setTotalTasks(0); // Force chunk-based calculation
        
        // When: increment from multiple threads
        Thread[] threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    progress.incrementProcessedChunks();
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Then: all increments are counted
        assertEquals(500, progress.getProcessedChunks());
        assertEquals(100.0, progress.getProgressPercentage(), 0.01);
    }

    @Test
    @DisplayName("Progress percentage calculation with both counters shows task precedence")
    void progressPercentage_prefersTasks_overChunks() {
        // Given: set up scenario where chunk % and task % differ
        progress.setTotalChunks(2);      // 2 chunks
        progress.setTotalTasks(6);        // 6 tasks (3 types × 2 chunks)
        
        // Simulate: completed 1 chunk (50% chunk-wise)
        progress.incrementProcessedChunks();
        
        // But only completed 2 tasks (33% task-wise)
        progress.incrementCompletedTasks();
        progress.incrementCompletedTasks();
        
        // When
        double percentage = progress.getProgressPercentage();
        
        // Then: uses task percentage (2/6 ≈ 33.33%), not chunk percentage (1/2 = 50%)
        assertEquals(33.33, percentage, 0.01);
    }
}

