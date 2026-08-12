package uk.gegc.quizmaker.features.document.application;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.application.impl.DocumentFileReferenceLookupImpl;
import uk.gegc.quizmaker.features.document.application.impl.LocalDocumentUploadStagingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("db-serial")
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import(DocumentFileReferenceLookupImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Document storage reconciliation with MySQL")
class DocumentStorageReconciliationMySqlIntegrationTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentFileReferenceLookup referenceLookup;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @TempDir
    Path storageRoot;

    private User owner;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = new User();
        owner.setUsername("doc_reconcile_" + suffix);
        owner.setEmail("doc_reconcile_" + suffix + "@example.com");
        owner.setHashedPassword("test-password");
        owner = userRepository.saveAndFlush(owner);
    }

    @AfterEach
    void cleanUp() {
        documentRepository.deleteAllInBatch();
        if (owner != null && owner.getId() != null) {
            userRepository.deleteById(owner.getId());
        }
    }

    @Test
    @DisplayName("Uses one projection query per 250 live candidates without per-file N+1 lookups")
    void resolvesLiveCandidatesWithBoundedQueryCount() throws IOException {
        DocumentProcessingLimits limits = limits();
        List<Path> files = IntStream.rangeClosed(1, 251)
                .mapToObj(index -> createExpiredPublishedFile("live-" + index + ".pdf", limits))
                .toList();
        documentRepository.saveAll(files.stream().map(this::document).toList());
        documentRepository.flush();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        new DocumentStorageReconciliationScheduler(
                referenceLookup,
                new LocalDocumentUploadStagingService(limits)
        ).reconcile();

        assertThat(files).allMatch(Files::exists);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    @Test
    @DisplayName("Preserves a file referenced by a transaction committed before the final recheck")
    void preservesReferenceCommittedBetweenBatchAndFinalCheck() throws Exception {
        DocumentProcessingLimits limits = limits();
        Path candidate = createExpiredPublishedFile("concurrent-publication.pdf", limits);
        CountDownLatch recheckEntered = new CountDownLatch(1);
        CountDownLatch releaseRecheck = new CountDownLatch(1);
        DocumentFileReferenceLookup barrierLookup = barrierLookup(recheckEntered, releaseRecheck);
        var scheduler = new DocumentStorageReconciliationScheduler(
                barrierLookup,
                new LocalDocumentUploadStagingService(limits)
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> reconciliation = executor.submit(scheduler::reconcile);
            assertThat(recheckEntered.await(5, TimeUnit.SECONDS)).isTrue();

            documentRepository.saveAndFlush(document(candidate));
            releaseRecheck.countDown();
            reconciliation.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRecheck.countDown();
            executor.shutdownNow();
        }

        assertThat(candidate).exists();
    }

    private DocumentFileReferenceLookup barrierLookup(
            CountDownLatch recheckEntered,
            CountDownLatch releaseRecheck
    ) {
        return new DocumentFileReferenceLookup() {
            @Override
            public Set<String> findReferencedPaths(Collection<String> candidatePaths) {
                return referenceLookup.findReferencedPaths(candidatePaths);
            }

            @Override
            public boolean isReferenced(String candidatePath) {
                recheckEntered.countDown();
                try {
                    if (!releaseRecheck.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Final reference-check barrier timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Final reference check was interrupted", e);
                }
                return referenceLookup.isReferenced(candidatePath);
            }
        };
    }

    private Document document(Path file) {
        LocalDateTime now = LocalDateTime.now();
        Document document = new Document();
        document.setOriginalFilename(file.getFileName().toString());
        document.setContentType("application/pdf");
        document.setFileSize(7L);
        document.setFilePath(file.toAbsolutePath().normalize().toString());
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedAt(now);
        document.setProcessedAt(now);
        document.setUploadedBy(owner);
        return document;
    }

    private Path createExpiredPublishedFile(String filename, DocumentProcessingLimits limits) {
        try {
            Path publishedDirectory = Files.createDirectories(storageRoot.resolve("published"));
            Path file = Files.writeString(publishedDirectory.resolve(filename), "fixture");
            Files.setLastModifiedTime(file, FileTime.from(
                    Instant.now().minus(limits.getStagingRetention()).minusSeconds(1)));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create reconciliation fixture", e);
        }
    }

    private DocumentProcessingLimits limits() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(storageRoot.toString());
        return limits;
    }
}
