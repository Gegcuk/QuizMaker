package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentStructureJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentStructureJobServiceImplConcurrencyTest {

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

    @Test
    void createJob_concurrentCallsForSameDocument_onlyOneSucceeds() throws InterruptedException {
        // Given
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // Mock repository to simulate race condition
        AtomicInteger saveCallCount = new AtomicInteger(0);
        
        when(jobRepository.existsByDocumentIdAndStatusIn(eq(documentId), any()))
            .thenReturn(false); // Initially no active job
        
        when(jobRepository.save(any(DocumentStructureJob.class)))
            .thenAnswer(invocation -> {
                // Simulate database save with slight delay
                Thread.sleep(10);
                
                // Simulate database constraint violation after first save
                int callCount = saveCallCount.incrementAndGet();
                if (callCount > 1) {
                    throw new RuntimeException("Duplicate key violation - job already exists");
                }
                
                DocumentStructureJob job = invocation.getArgument(0);
                job.setId(jobId);
                return job;
            });

        // Create tasks for concurrent execution
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    try {
                        DocumentStructureJob result = jobService.createJob(user, documentId, DocumentNode.Strategy.AI);
                        successCount.incrementAndGet();
                        assertThat(result).isNotNull();
                        assertThat(result.getId()).isEqualTo(jobId);
                    } catch (ValidationException e) {
                        failureCount.incrementAndGet();
                        exceptions.add(e);
                    } catch (RuntimeException e) {
                        // Handle database constraint violations
                        if (e.getMessage().contains("Duplicate key violation")) {
                            failureCount.incrementAndGet();
                            exceptions.add(e);
                        } else {
                            throw e;
                        }
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    endLatch.countDown();
                }
            }, executor);
            
            futures.add(future);
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Then - Only one should succeed, others should fail with validation exception
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);
        
        // Verify all failures are due to either validation or database constraint violations
        for (Exception exception : exceptions) {
            if (exception instanceof ValidationException) {
                assertThat(exception.getMessage()).contains("already an active structure extraction job");
            } else if (exception instanceof RuntimeException) {
                assertThat(exception.getMessage()).contains("Duplicate key violation");
            }
        }
    }

    @Test
    void createJob_concurrentCallsWithDatabaseRaceCondition_handlesCorrectly() throws InterruptedException {
        // Given
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Mock repository to simulate database race condition
        when(jobRepository.existsByDocumentIdAndStatusIn(eq(documentId), any()))
            .thenReturn(false); // Initially no active job
        
        when(jobRepository.save(any(DocumentStructureJob.class)))
            .thenAnswer(invocation -> {
                // Simulate database constraint violation for duplicate key
                if (successCount.get() > 0) {
                    throw new RuntimeException("Duplicate key violation");
                }
                
                DocumentStructureJob job = invocation.getArgument(0);
                job.setId(jobId);
                successCount.incrementAndGet();
                return job;
            });

        // Create tasks for concurrent execution
        for (int i = 0; i < threadCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    try {
                        jobService.createJob(user, documentId, DocumentNode.Strategy.AI);
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            }, executor);
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Then - Only one should succeed, others should fail
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    void createJob_concurrentCallsWithDifferentDocuments_allSucceed() throws InterruptedException {
        // Given
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        List<UUID> createdJobIds = new ArrayList<>();

        // Mock repository for different documents
        when(jobRepository.existsByDocumentIdAndStatusIn(any(UUID.class), any()))
            .thenReturn(false); // No active jobs for any document
        
        when(jobRepository.save(any(DocumentStructureJob.class)))
            .thenAnswer(invocation -> {
                DocumentStructureJob job = invocation.getArgument(0);
                UUID newJobId = UUID.randomUUID();
                job.setId(newJobId);
                createdJobIds.add(newJobId);
                successCount.incrementAndGet();
                return job;
            });

        // Create tasks for concurrent execution with different document IDs
        for (int i = 0; i < threadCount; i++) {
            final UUID docId = UUID.randomUUID();
            
            CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    DocumentStructureJob result = jobService.createJob(user, docId, DocumentNode.Strategy.AI);
                    assertThat(result).isNotNull();
                    assertThat(result.getDocumentId()).isEqualTo(docId);
                    
                } catch (Exception e) {
                    // Should not throw any exceptions
                    throw new RuntimeException("Unexpected exception", e);
                } finally {
                    endLatch.countDown();
                }
            }, executor);
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Then - All should succeed
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(createdJobIds).hasSize(threadCount);
        assertThat(createdJobIds).doesNotHaveDuplicates();
    }

    @Test
    void createJob_concurrentCallsWithMixedScenarios_handlesCorrectly() throws InterruptedException {
        // Given
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();

        // Mock repository with mixed scenarios
        when(jobRepository.existsByDocumentIdAndStatusIn(eq(doc1), any()))
            .thenReturn(false); // No active job for doc1
        
        when(jobRepository.existsByDocumentIdAndStatusIn(eq(doc2), any()))
            .thenReturn(true); // Already has active job for doc2
        
        when(jobRepository.save(any(DocumentStructureJob.class)))
            .thenAnswer(invocation -> {
                DocumentStructureJob job = invocation.getArgument(0);
                job.setId(UUID.randomUUID());
                successCount.incrementAndGet();
                return job;
            });

        // Create tasks: 4 threads for doc1 (should succeed), 4 threads for doc2 (should fail)
        for (int i = 0; i < threadCount; i++) {
            final UUID docId = (i < 4) ? doc1 : doc2;
            
            CompletableFuture.runAsync(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    
                    try {
                        DocumentStructureJob result = jobService.createJob(user, docId, DocumentNode.Strategy.AI);
                        assertThat(result).isNotNull();
                        assertThat(result.getDocumentId()).isEqualTo(docId);
                    } catch (ValidationException e) {
                        failureCount.incrementAndGet();
                        assertThat(e.getMessage()).contains("already an active structure extraction job");
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            }, executor);
        }

        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Then - 4 should succeed (doc1), 4 should fail (doc2)
        assertThat(successCount.get()).isEqualTo(4);
        assertThat(failureCount.get()).isEqualTo(4);
    }
}
