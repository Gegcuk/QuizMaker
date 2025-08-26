package uk.gegc.quizmaker.features.document.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DocumentStructureJobRepositoryTest {

    @Autowired
    private DocumentStructureJobRepository jobRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User user1;
    private User user2;
    private Document document1;
    private Document document2;
    private DocumentStructureJob job1;
    private DocumentStructureJob job2;
    private DocumentStructureJob job3;
    private DocumentStructureJob job4;

    @BeforeEach
    void setUp() {
        // Create test users
        user1 = new User();
        user1.setUsername("user1");
        user1.setEmail("user1@test.com");
        user1.setHashedPassword("password");
        entityManager.persistAndFlush(user1);

        user2 = new User();
        user2.setUsername("user2");
        user2.setEmail("user2@test.com");
        user2.setHashedPassword("password");
        entityManager.persistAndFlush(user2);

        // Create test documents
        document1 = new Document();
        document1.setOriginalFilename("test1.pdf");
        document1.setContentType("application/pdf");
        document1.setFileSize(1024L);
        document1.setFilePath("/uploads/test1.pdf");
        document1.setStatus(Document.DocumentStatus.PROCESSED);
        document1.setUploadedBy(user1);
        document1.setUploadedAt(LocalDateTime.now());
        entityManager.persistAndFlush(document1);

        document2 = new Document();
        document2.setOriginalFilename("test2.pdf");
        document2.setContentType("application/pdf");
        document2.setFileSize(2048L);
        document2.setFilePath("/uploads/test2.pdf");
        document2.setStatus(Document.DocumentStatus.PROCESSED);
        document2.setUploadedBy(user2);
        document2.setUploadedAt(LocalDateTime.now());
        entityManager.persistAndFlush(document2);

        // Create test jobs
        job1 = createJob(user1, document1, DocumentStructureJob.StructureExtractionStatus.PENDING, 
                        LocalDateTime.now().minusHours(2), null, 0, null);
        job2 = createJob(user1, document1, DocumentStructureJob.StructureExtractionStatus.PROCESSING, 
                        LocalDateTime.now().minusHours(1), null, 50, null);
        job3 = createJob(user2, document2, DocumentStructureJob.StructureExtractionStatus.COMPLETED, 
                        LocalDateTime.now().minusHours(3), LocalDateTime.now().minusMinutes(30), 100, 120L);
        job4 = createJob(user1, document2, DocumentStructureJob.StructureExtractionStatus.FAILED, 
                        LocalDateTime.now().minusHours(4), LocalDateTime.now().minusMinutes(45), 0, 300L);

        entityManager.flush();
    }

    private DocumentStructureJob createJob(User user, Document document, DocumentStructureJob.StructureExtractionStatus status,
                                         LocalDateTime startedAt, LocalDateTime completedAt, int progress, Long extractionTimeSeconds) {
        DocumentStructureJob job = new DocumentStructureJob();
        job.setUser(user);
        job.setDocumentId(document.getId());
        job.setStatus(status);
        job.setStartedAt(startedAt);
        job.setCompletedAt(completedAt);
        job.setProgressPercentage((double) progress);
        job.setExtractionTimeSeconds(extractionTimeSeconds);
        job.setStrategy(DocumentNode.Strategy.AI);
        job.setCurrentPhase("Test Phase");
        job.setSourceVersionHash("test-hash");
        if (status == DocumentStructureJob.StructureExtractionStatus.COMPLETED) {
            job.setNodesExtracted(10);
        }
        return entityManager.persistAndFlush(job);
    }

    @Test
    void findStuckJobs_returnsOnlyJobsOlderThanCutoffInActiveStatuses() {
        // Given
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<DocumentStructureJob.StructureExtractionStatus> activeStatuses = Arrays.asList(
                DocumentStructureJob.StructureExtractionStatus.PENDING,
                DocumentStructureJob.StructureExtractionStatus.PROCESSING
        );

        // When
        List<DocumentStructureJob> stuckJobs = jobRepository.findStuckJobs(cutoff, activeStatuses);

        // Then
        assertThat(stuckJobs).hasSize(2);
        assertThat(stuckJobs).extracting("id").containsExactlyInAnyOrder(job1.getId(), job2.getId());
        assertThat(stuckJobs).allMatch(job -> job.getStartedAt().isBefore(cutoff));
        assertThat(stuckJobs).allMatch(job -> activeStatuses.contains(job.getStatus()));
    }

    @Test
    void deleteOldCompletedJobs_removesOnlyTerminalJobsBeforeCutoff() {
        // Given
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(20);
        List<DocumentStructureJob.StructureExtractionStatus> terminalStatuses = Arrays.asList(
                DocumentStructureJob.StructureExtractionStatus.COMPLETED,
                DocumentStructureJob.StructureExtractionStatus.FAILED,
                DocumentStructureJob.StructureExtractionStatus.CANCELLED
        );

        // When
        int deletedCount = jobRepository.deleteOldCompletedJobs(cutoff, terminalStatuses);

        // Then
        assertThat(deletedCount).isEqualTo(2); // job3 and job4 should be deleted
        assertThat(jobRepository.findById(job1.getId())).isPresent(); // PENDING job should remain
        assertThat(jobRepository.findById(job2.getId())).isPresent(); // PROCESSING job should remain
        assertThat(jobRepository.findById(job3.getId())).isEmpty(); // COMPLETED job should be deleted
        assertThat(jobRepository.findById(job4.getId())).isEmpty(); // FAILED job should be deleted
    }

    @Test
    void getAverageExtractionTimeByUsername_returnsExpectedValue() {
        // Given
        // job3 has extractionTimeSeconds = 120L, job4 has extractionTimeSeconds = 300L
        // Both are for different users, so we'll test with user2

        // When
        Double averageTime = jobRepository.getAverageExtractionTimeByUsername("user2");

        // Then
        assertThat(averageTime).isEqualTo(120.0); // Only job3 belongs to user2
    }

    @Test
    void getTotalNodesExtractedByUsername_returnsExpectedValue() {
        // Given
        // job3 has nodesExtracted = 10, belongs to user2

        // When
        Long totalNodes = jobRepository.getTotalNodesExtractedByUsername("user2");

        // Then
        assertThat(totalNodes).isEqualTo(10L);
    }

    @Test
    void getLastJobCreatedByUsername_returnsExpectedValue() {
        // Given
        // job1 started 2 hours ago, job2 started 1 hour ago, job4 started 4 hours ago
        // All belong to user1

        // When
        Optional<LocalDateTime> lastJobTime = jobRepository.getLastJobCreatedByUsername("user1");

        // Then
        assertThat(lastJobTime).isPresent();
        assertThat(lastJobTime.get()).isCloseTo(job2.getStartedAt(), within(1, ChronoUnit.MICROS)); // job2 is the most recent
    }

    @Test
    void existsByDocumentIdAndStatusIn_worksAsGateForActiveJobs() {
        // Given
        List<DocumentStructureJob.StructureExtractionStatus> activeStatuses = Arrays.asList(
                DocumentStructureJob.StructureExtractionStatus.PENDING,
                DocumentStructureJob.StructureExtractionStatus.PROCESSING
        );

        // When
        boolean hasActiveJob = jobRepository.existsByDocumentIdAndStatusIn(document1.getId(), activeStatuses);

        // Then
        assertThat(hasActiveJob).isTrue(); // document1 has both PENDING and PROCESSING jobs

        // Test with document2 (only has COMPLETED and FAILED jobs)
        boolean hasActiveJob2 = jobRepository.existsByDocumentIdAndStatusIn(document2.getId(), activeStatuses);
        assertThat(hasActiveJob2).isFalse();
    }

    @Test
    void findByUser_UsernameOrderByStartedAtDesc_returnsCorrectOrder() {
        // Given
        PageRequest pageRequest = PageRequest.of(0, 10);

        // When
        Page<DocumentStructureJob> jobs = jobRepository.findByUser_UsernameOrderByStartedAtDesc("user1", pageRequest);

        // Then
        assertThat(jobs.getContent()).hasSize(3); // user1 has 3 jobs
        assertThat(jobs.getContent()).extracting("id")
                .containsExactly(job2.getId(), job1.getId(), job4.getId()); // Ordered by startedAt desc
    }

    @Test
    void findByDocumentIdOrderByStartedAtDesc_returnsCorrectOrder() {
        // When
        List<DocumentStructureJob> jobs = jobRepository.findByDocumentIdOrderByStartedAtDesc(document1.getId());

        // Then
        assertThat(jobs).hasSize(2); // document1 has 2 jobs
        assertThat(jobs).extracting("id")
                .containsExactly(job2.getId(), job1.getId()); // Ordered by startedAt desc
    }

    @Test
    void findByIdAndUser_Username_returnsJobForCorrectUser() {
        // When
        Optional<DocumentStructureJob> foundJob = jobRepository.findByIdAndUser_Username(job1.getId(), "user1");

        // Then
        assertThat(foundJob).isPresent();
        assertThat(foundJob.get().getId()).isEqualTo(job1.getId());

        // Test with wrong user
        Optional<DocumentStructureJob> wrongUserJob = jobRepository.findByIdAndUser_Username(job1.getId(), "user2");
        assertThat(wrongUserJob).isEmpty();
    }

    @Test
    void findByStatusOrderByStartedAtAsc_returnsCorrectOrder() {
        // When
        List<DocumentStructureJob> pendingJobs = jobRepository.findByStatusOrderByStartedAtAsc(
                DocumentStructureJob.StructureExtractionStatus.PENDING);

        // Then
        assertThat(pendingJobs).hasSize(1);
        assertThat(pendingJobs.get(0).getId()).isEqualTo(job1.getId());
    }

    @Test
    void countByUser_Username_returnsCorrectCount() {
        // When
        long count = jobRepository.countByUser_Username("user1");

        // Then
        assertThat(count).isEqualTo(3); // user1 has 3 jobs
    }

    @Test
    void countByUser_UsernameAndStatus_returnsCorrectCount() {
        // When
        long pendingCount = jobRepository.countByUser_UsernameAndStatus("user1", 
                DocumentStructureJob.StructureExtractionStatus.PENDING);

        // Then
        assertThat(pendingCount).isEqualTo(1); // user1 has 1 PENDING job
    }

    @Test
    void findFirstByDocumentIdAndStatusOrderByCompletedAtDesc_returnsMostRecentCompleted() {
        // Given - create another completed job for document2
        DocumentStructureJob job5 = createJob(user2, document2, DocumentStructureJob.StructureExtractionStatus.COMPLETED,
                LocalDateTime.now().minusHours(5), LocalDateTime.now().minusMinutes(15), 100, 90L);

        // When
        Optional<DocumentStructureJob> mostRecentCompleted = jobRepository.findFirstByDocumentIdAndStatusOrderByCompletedAtDesc(
                document2.getId(), DocumentStructureJob.StructureExtractionStatus.COMPLETED);

        // Then
        assertThat(mostRecentCompleted).isPresent();
        assertThat(mostRecentCompleted.get().getId()).isEqualTo(job5.getId()); // job5 completed more recently than job3
    }
}
