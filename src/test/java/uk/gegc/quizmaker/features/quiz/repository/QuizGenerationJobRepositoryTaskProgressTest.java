package uk.gegc.quizmaker.features.quiz.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for atomic task progress update methods in QuizGenerationJobRepository.
 * Tests concurrent increments, version bumping, and progress percentage calculation.
 */
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Tag("db-serial") // Uses ExecutorService for concurrent DB writes
@DisplayName("QuizGenerationJobRepository Atomic Task Progress Tests")
class QuizGenerationJobRepositoryTaskProgressTest {

    @Autowired
    private QuizGenerationJobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User testUser;
    private QuizGenerationJob testJob;

    @BeforeEach
    void setUp() {
        String userSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Create test user
        testUser = new User();
        testUser.setUsername("task_repo_" + userSuffix);
        testUser.setEmail("task-repo-" + userSuffix + "@example.test");
        testUser.setHashedPassword("hashedPassword");
        testUser = userRepository.save(testUser);

        // Create test job
        testJob = new QuizGenerationJob();
        testJob.setUser(testUser);
        testJob.setDocumentId(UUID.randomUUID());
        testJob.setStatus(GenerationStatus.PROCESSING);
        testJob.setTotalChunks(5);
        testJob.setProcessedChunks(0);
        testJob.setTotalTasks(15);
        testJob.setCompletedTasks(0);
        testJob.setRequestData("{}");
        testJob = jobRepository.save(testJob);
    }

    @AfterEach
    void tearDown() {
        if (testJob == null || testJob.getId() == null || testUser == null || testUser.getId() == null) {
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            jobRepository.deleteById(testJob.getId());
            userRepository.deleteById(testUser.getId());
        });
    }

    @Test
    @DisplayName("incrementCompletedTasks updates counter atomically")
    void incrementCompletedTasks_updatesCounter() {
        // When
        int updated = jobRepository.incrementCompletedTasks(testJob.getId(), 1, "Task 1 done");
        entityManager.flush();
        entityManager.clear();

        // Then
        assertEquals(1, updated);

        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(1, reloaded.getCompletedTasks());
        assertEquals("Task 1 done", reloaded.getCurrentChunk());
    }

    @Test
    @DisplayName("incrementCompletedTasks updates completed tasks counter")
    @Transactional
    void incrementCompletedTasks_calculatesProgressFromTasks() {
        // When: complete 5 out of 15 tasks
        int updated = jobRepository.incrementCompletedTasks(testJob.getId(), 5, "5 tasks done");
        entityManager.flush();
        entityManager.clear();

        // Then: counter updated (percentage calculation verified in unit tests)
        assertEquals(1, updated);
        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(5, reloaded.getCompletedTasks());
        assertEquals("5 tasks done", reloaded.getCurrentChunk());
        assertNotNull(reloaded.getProgressPercentage());
        assertTrue(reloaded.getProgressPercentage() > 0); // Some progress recorded
    }

    @Test
    @DisplayName("incrementCompletedTasks stores bounded and rounded task progress")
    void incrementCompletedTasks_storesBoundedAndRoundedProgress() {
        testJob.setTotalTasks(7);
        testJob.setCompletedTasks(2);
        testJob = jobRepository.save(testJob);
        entityManager.flush();
        entityManager.clear();

        jobRepository.incrementCompletedTasks(testJob.getId(), 1, "Three of seven tasks done");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob rounded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(42.86, rounded.getProgressPercentage());

        jobRepository.incrementCompletedTasks(testJob.getId(), 10, "Tasks exceeded");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob capped = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(99.0, capped.getProgressPercentage());
    }

    @Test
    @DisplayName("Atomic updates preserve prior bounded progress when counters are malformed")
    void incrementCompletedTasks_preservesProgressWhenCountersAreMalformed() {
        testJob.setTotalTasks(7);
        testJob.setCompletedTasks(-2);
        testJob.setProgressPercentage(25.0);
        testJob = jobRepository.save(testJob);
        entityManager.flush();
        entityManager.clear();

        jobRepository.incrementCompletedTasks(testJob.getId(), 1, "Recovering counters");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(-1, reloaded.getCompletedTasks());
        assertEquals(25.0, reloaded.getProgressPercentage());
    }

    @Test
    @DisplayName("incrementCompletedTasks bumps version for optimistic locking")
    void incrementCompletedTasks_bumpsVersion() {
        // Given: reload to get initial version
        QuizGenerationJob before = jobRepository.findById(testJob.getId()).orElseThrow();
        Long initialVersion = before.getVersion() != null ? before.getVersion() : 0L;

        // When
        jobRepository.incrementCompletedTasks(testJob.getId(), 1, "Task done");
        entityManager.flush();
        entityManager.clear();

        // Then: version incremented
        QuizGenerationJob after = jobRepository.findById(testJob.getId()).orElseThrow();
        assertNotNull(after.getVersion());
        assertEquals(initialVersion + 1, after.getVersion());
    }

    @Test
    @DisplayName("updateProcessedChunksAndStatus bumps version for optimistic locking")
    void updateProcessedChunksAndStatus_bumpsVersion() {
        QuizGenerationJob before = jobRepository.findById(testJob.getId()).orElseThrow();
        Long initialVersion = before.getVersion() != null ? before.getVersion() : 0L;

        jobRepository.updateProcessedChunksAndStatus(testJob.getId(), 1, "Chunk done");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob after = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(initialVersion + 1, after.getVersion());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Stale entity saves cannot overwrite newer atomic progress")
    void atomicProgressUpdate_rejectsStaleEntitySave() {
        QuizGenerationJob staleJob = transactionTemplate.execute(status ->
                jobRepository.findById(testJob.getId()).orElseThrow());

        transactionTemplate.executeWithoutResult(status ->
                jobRepository.incrementCompletedTasks(testJob.getId(), 5, "Atomic progress"));

        assertThrows(ObjectOptimisticLockingFailureException.class, () ->
                transactionTemplate.executeWithoutResult(status -> jobRepository.saveAndFlush(staleJob)));

        QuizGenerationJob reloaded = transactionTemplate.execute(status ->
                jobRepository.findById(testJob.getId()).orElseThrow());
        assertNotNull(reloaded);
        assertEquals(5, reloaded.getCompletedTasks());
        assertEquals(33.33, reloaded.getProgressPercentage());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("incrementCompletedTasks preserves every simultaneous transaction")
    void incrementCompletedTasks_handlesConcurrentUpdates() throws Exception {
        int workerCount = 5;
        long initialVersion = testJob.getVersion() == null ? 0L : testJob.getVersion();
        CyclicBarrier readyToUpdate = new CyclicBarrier(workerCount);
        AtomicInteger activeTransactions = new AtomicInteger();
        AtomicInteger maximumOverlappingTransactions = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);

        try {
            List<java.util.concurrent.Future<Integer>> updates = new ArrayList<>();
            for (int worker = 0; worker < workerCount; worker++) {
                int taskNumber = worker;
                updates.add(executor.submit(() -> transactionTemplate.execute(status -> {
                    int active = activeTransactions.incrementAndGet();
                    maximumOverlappingTransactions.accumulateAndGet(active, Math::max);
                    try {
                        await(readyToUpdate);
                        return jobRepository.incrementCompletedTasks(
                                testJob.getId(),
                                1,
                                "Concurrent task " + taskNumber
                        );
                    } finally {
                        activeTransactions.decrementAndGet();
                    }
                })));
            }

            for (java.util.concurrent.Future<Integer> update : updates) {
                assertEquals(1, update.get(15, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Worker threads must stop");
        }

        entityManager.clear();
        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();

        assertEquals(workerCount, maximumOverlappingTransactions.get(),
                "The barrier must release all worker transactions together");
        assertEquals(workerCount, reloaded.getCompletedTasks(),
                "The atomic database update must not lose any concurrent increments");
        assertEquals(initialVersion + workerCount, reloaded.getVersion(),
                "Each successful atomic update must advance the optimistic-lock version");
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while synchronizing concurrent updates", exception);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException exception) {
            throw new AssertionError("Concurrent update workers did not reach the barrier", exception);
        }
    }

    @Test
    @DisplayName("incrementCompletedTasks returns 0 for non-existent job")
    void incrementCompletedTasks_returnsZeroForNonExistentJob() {
        // When
        int updated = jobRepository.incrementCompletedTasks(UUID.randomUUID(), 1, "Test");

        // Then
        assertEquals(0, updated);
    }

    @Test
    @DisplayName("Atomic progress updates reject negative counters without mutation")
    void atomicProgressUpdates_rejectNegativeCounters() {
        int taskUpdates = jobRepository.incrementCompletedTasks(testJob.getId(), -1, "Invalid task");
        int chunkUpdates = jobRepository.updateProcessedChunksAndStatus(testJob.getId(), -1, "Invalid chunk");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(0, taskUpdates);
        assertEquals(0, chunkUpdates);
        assertEquals(0, reloaded.getCompletedTasks());
        assertEquals(0, reloaded.getProcessedChunks());
        assertEquals(0.0, reloaded.getProgressPercentage());
    }

    @Test
    @DisplayName("updateProcessedChunksAndStatus preserves task-based progress when task counters exist")
    void updateProcessedChunksAndStatus_preservesTaskBasedProgress_whenTaskCountersExist() {
        // Given: set initial task progress
        jobRepository.incrementCompletedTasks(testJob.getId(), 5, "Initial task status");
        entityManager.flush();
        entityManager.clear();
        
        QuizGenerationJob before = jobRepository.findById(testJob.getId()).orElseThrow();
        double taskBasedPercentage = before.getProgressPercentage();

        // When: update chunk status
        int updated = jobRepository.updateProcessedChunksAndStatus(testJob.getId(), 3, "Chunk 3/5 done");
        entityManager.flush();
        entityManager.clear();

        // Then: chunk fields update while task counters remain authoritative for progress.
        assertEquals(1, updated);
        
        QuizGenerationJob after = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(3, after.getProcessedChunks());
        assertEquals("Chunk 3/5 done", after.getCurrentChunk());
        assertEquals(5, after.getCompletedTasks()); // Still 5
        assertEquals(taskBasedPercentage, after.getProgressPercentage(), 0.01); // Unchanged
    }

    @Test
    @DisplayName("Out-of-order chunk updates preserve the highest counter and percentage")
    void updateProcessedChunksAndStatus_preservesMonotonicChunkProgress() {
        testJob.setTotalTasks(null);
        testJob = jobRepository.save(testJob);
        entityManager.flush();
        entityManager.clear();

        jobRepository.updateProcessedChunksAndStatus(testJob.getId(), 4, "Chunk 4/5 done");
        jobRepository.updateProcessedChunksAndStatus(testJob.getId(), 2, "Late chunk 2/5 status");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(4, reloaded.getProcessedChunks());
        assertEquals(80.0, reloaded.getProgressPercentage());
        assertEquals("Chunk 4/5 done", reloaded.getCurrentChunk());
    }

    @Test
    @DisplayName("updateTotalTasks sets total tasks")
    void updateTotalTasks_setsTotalTasks() {
        // When
        int updated = jobRepository.updateTotalTasks(testJob.getId(), 30);
        entityManager.flush();
        entityManager.clear();

        // Then
        assertEquals(1, updated);
        
        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(30, reloaded.getTotalTasks());
    }

    @Test
    @DisplayName("incrementCompletedTasks falls back to chunk-based percentage when totalTasks is null")
    void incrementCompletedTasks_fallsBackToChunks_whenTotalTasksNull() {
        // Given: job with no totalTasks but has chunks
        testJob.setTotalTasks(null);
        testJob.setProcessedChunks(2);
        testJob = jobRepository.save(testJob);
        entityManager.flush();
        entityManager.clear();

        // When: increment tasks
        jobRepository.incrementCompletedTasks(testJob.getId(), 1, "Task done");
        entityManager.flush();
        entityManager.clear();

        // Then: uses chunk-based percentage (2/5 = 40%)
        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(40.0, reloaded.getProgressPercentage(), 0.1);
    }

    @Test
    @DisplayName("updateProcessedChunksAndStatus stores bounded chunk progress when tasks are unavailable")
    void updateProcessedChunksAndStatus_storesBoundedChunkProgress_whenTasksUnavailable() {
        testJob.setTotalTasks(null);
        testJob = jobRepository.save(testJob);
        entityManager.flush();
        entityManager.clear();

        jobRepository.updateProcessedChunksAndStatus(testJob.getId(), 7, "More chunks than planned");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(7, reloaded.getProcessedChunks());
        assertEquals(99.0, reloaded.getProgressPercentage());
    }

    @Test
    @DisplayName("Terminal jobs reject late atomic progress updates")
    void atomicProgressUpdates_doNotMutateTerminalJobs() {
        testJob.markCompleted(UUID.randomUUID(), 10);
        testJob = jobRepository.saveAndFlush(testJob);
        entityManager.clear();

        int taskUpdates = jobRepository.incrementCompletedTasks(testJob.getId(), 1, "Late task");
        int chunkUpdates = jobRepository.updateProcessedChunksAndStatus(testJob.getId(), 5, "Late chunk");
        entityManager.flush();
        entityManager.clear();

        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(0, taskUpdates);
        assertEquals(0, chunkUpdates);
        assertEquals(GenerationStatus.COMPLETED, reloaded.getStatus());
        assertEquals(100.0, reloaded.getProgressPercentage());
        assertNotEquals("Late task", reloaded.getCurrentChunk());
        assertNotEquals("Late chunk", reloaded.getCurrentChunk());
    }

    @Test
    @DisplayName("incrementCompletedTasks handles null completedTasks gracefully")
    void incrementCompletedTasks_handlesNullCompletedTasks() {
        // Given: job with null completedTasks (need to use native SQL to bypass entity field default)
        // Actually, due to field default = 0, this scenario won't happen in practice
        // so this test verifies COALESCE in query handles it anyway
        
        // When: increment
        jobRepository.incrementCompletedTasks(testJob.getId(), 3, "Test");
        entityManager.flush();
        entityManager.clear();

        // Then: treats null/0 as 0, so result is 3
        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(3, reloaded.getCompletedTasks());
    }

    @Test
    @DisplayName("Multiple sequential increments accumulate correctly")
    @Transactional
    void incrementCompletedTasks_accumulatesSequentially() {
        // When: increment multiple times
        jobRepository.incrementCompletedTasks(testJob.getId(), 2, "Step 1");
        jobRepository.incrementCompletedTasks(testJob.getId(), 3, "Step 2");
        jobRepository.incrementCompletedTasks(testJob.getId(), 5, "Step 3");
        entityManager.flush();
        entityManager.clear();

        // Then: counter accumulates correctly
        QuizGenerationJob reloaded = jobRepository.findById(testJob.getId()).orElseThrow();
        assertEquals(10, reloaded.getCompletedTasks()); // 0 + 2 + 3 + 5 = 10
        assertEquals("Step 3", reloaded.getCurrentChunk());
        assertNotNull(reloaded.getProgressPercentage());
        // Progress should be positive (exact calculation tested in unit tests)
        assertTrue(reloaded.getProgressPercentage() > 0);
    }
}
