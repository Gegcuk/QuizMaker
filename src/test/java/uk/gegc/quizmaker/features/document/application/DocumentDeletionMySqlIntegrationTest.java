package uk.gegc.quizmaker.features.document.application;

import jakarta.persistence.EntityManager;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.document.application.impl.AfterCommitDocumentSourceFileCleanup;
import uk.gegc.quizmaker.features.document.application.impl.DocumentDeletionServiceImpl;
import uk.gegc.quizmaker.features.document.application.impl.DocumentFileReferenceLookupImpl;
import uk.gegc.quizmaker.features.document.application.impl.LocalDocumentUploadStagingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Tag("db-serial")
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import({
        DocumentDeletionServiceImpl.class,
        AfterCommitDocumentSourceFileCleanup.class,
        DocumentFileReferenceLookupImpl.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Document deletion transaction boundary with MySQL")
class DocumentDeletionMySqlIntegrationTest {

    @Autowired
    private DocumentDeletionService documentDeletionService;

    @Autowired
    private DocumentFileReferenceLookup referenceLookup;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private DocumentUploadStagingService uploadStagingService;

    @TempDir
    Path storageRoot;

    private User owner;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = new User();
        owner.setUsername("doc_delete_" + suffix);
        owner.setEmail("doc_delete_" + suffix + "@example.com");
        owner.setHashedPassword("test-password");
        owner = userRepository.saveAndFlush(owner);

        doAnswer(invocation -> {
            Files.deleteIfExists(invocation.getArgument(0, Path.class));
            return true;
        }).when(uploadStagingService).discard(any(Path.class));
    }

    @AfterEach
    void cleanUp() {
        chunkRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
        if (owner != null && owner.getId() != null) {
            userRepository.deleteById(owner.getId());
        }
    }

    @Test
    @DisplayName("Removes chunks, document, and source only after the database commit succeeds")
    void committedDeletionRemovesDatabaseStateAndSource() throws IOException {
        Path source = createPublishedFile("committed.pdf");
        Document document = persistDocument(source);
        DocumentChunk chunk = persistChunk(document);

        documentDeletionService.deleteDocument(owner.getUsername(), document.getId());

        assertThat(documentRepository.findById(document.getId())).isEmpty();
        assertThat(chunkRepository.findById(chunk.getId())).isEmpty();
        assertThat(source).doesNotExist();
        verify(uploadStagingService).discard(source);
    }

    @Test
    @DisplayName("Preserves the committed document, chunks, and source when the enclosing transaction rolls back")
    void rolledBackDeletionPreservesDatabaseStateAndSource() throws IOException {
        Path source = createPublishedFile("rolled-back.pdf");
        Document document = persistDocument(source);
        DocumentChunk chunk = persistChunk(document);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            documentDeletionService.deleteDocument(owner.getUsername(), document.getId());
            status.setRollbackOnly();
        });

        assertThat(documentRepository.findById(document.getId())).isPresent();
        assertThat(chunkRepository.findById(chunk.getId())).isPresent();
        assertThat(source).exists();
        verify(uploadStagingService, never()).discard(source);
    }

    @Test
    @DisplayName("Preserves database state and source when MySQL rejects later work in the deletion transaction")
    void databaseFailureAfterDeletionSchedulingPreservesDatabaseStateAndSource() throws IOException {
        Path source = createPublishedFile("database-failure.pdf");
        Document document = persistDocument(source);
        DocumentChunk originalChunk = persistChunk(document);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            documentDeletionService.deleteDocument(owner.getUsername(), document.getId());
            Document missingDocument = entityManager.getReference(Document.class, UUID.randomUUID());
            chunkRepository.saveAndFlush(chunk(missingDocument, 99));
        })).isInstanceOf(RuntimeException.class);

        assertThat(documentRepository.findById(document.getId())).isPresent();
        assertThat(chunkRepository.findById(originalChunk.getId())).isPresent();
        assertThat(source).exists();
        verify(uploadStagingService, never()).discard(source);
    }

    @Test
    @DisplayName("Commits deletion when immediate cleanup fails and lets reconciliation remove the orphan")
    void cleanupFailureLeavesRecoverableOrphan() throws IOException {
        Path source = createPublishedFile("deferred.pdf");
        Document document = persistDocument(source);
        doThrow(new IllegalStateException("simulated storage failure"))
                .when(uploadStagingService).discard(source);

        assertThatCode(() -> documentDeletionService.deleteDocument(owner.getUsername(), document.getId()))
                .doesNotThrowAnyException();

        assertThat(documentRepository.findById(document.getId())).isEmpty();
        assertThat(source).exists();

        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(storageRoot.toString());
        Files.setLastModifiedTime(source, FileTime.from(
                Instant.now().minus(limits.getStagingRetention()).minusSeconds(1)));

        new DocumentStorageReconciliationScheduler(
                referenceLookup,
                new LocalDocumentUploadStagingService(limits, mock(DocumentIngestionMetrics.class)),
                mock(DocumentIngestionMetrics.class)
        ).reconcile();

        assertThat(source).doesNotExist();
    }

    @Test
    @DisplayName("Serializes concurrent duplicate deletes so one succeeds and one observes not found")
    void concurrentDuplicateDeletesPreserveTheDatabaseStorageInvariant() throws Exception {
        Path source = createPublishedFile("concurrent.pdf");
        Document document = persistDocument(source);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() ->
                    deleteConcurrently(document.getId(), ready, start));
            Future<Throwable> second = executor.submit(() ->
                    deleteConcurrently(document.getId(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable firstOutcome = first.get(10, TimeUnit.SECONDS);
            Throwable secondOutcome = second.get(10, TimeUnit.SECONDS);
            assertThat(Stream.of(firstOutcome, secondOutcome).filter(Objects::isNull).count()).isEqualTo(1);
            assertThat(Stream.of(firstOutcome, secondOutcome).filter(Objects::nonNull).toList())
                    .singleElement()
                    .isInstanceOf(DocumentNotFoundException.class);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(documentRepository.findById(document.getId())).isEmpty();
        assertThat(source).doesNotExist();
        verify(uploadStagingService, times(1)).discard(source);
    }

    @Test
    @DisplayName("Keeps deletion reads bounded when a document has many chunks")
    void deletionDoesNotIntroducePerChunkReadQueries() throws IOException {
        Path source = createPublishedFile("many-chunks.pdf");
        Document document = persistDocument(source);
        chunkRepository.saveAllAndFlush(IntStream.range(0, 25)
                .mapToObj(index -> chunk(document, index))
                .toList());
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        documentDeletionService.deleteDocument(owner.getUsername(), document.getId());

        assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(3);
        assertThat(statistics.getCollectionFetchCount()).isLessThanOrEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(2);
        assertThat(documentRepository.findById(document.getId())).isEmpty();
        assertThat(source).doesNotExist();
    }

    private Throwable deleteConcurrently(UUID documentId, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return new IllegalStateException("Concurrent delete start barrier timed out");
            }
            documentDeletionService.deleteDocument(owner.getUsername(), documentId);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Path createPublishedFile(String filename) throws IOException {
        Path publishedDirectory = Files.createDirectories(storageRoot.resolve("published"));
        return Files.writeString(publishedDirectory.resolve(filename), "fixture")
                .toAbsolutePath()
                .normalize();
    }

    private Document persistDocument(Path source) {
        LocalDateTime now = LocalDateTime.now();
        Document document = new Document();
        document.setOriginalFilename(source.getFileName().toString());
        document.setContentType("application/pdf");
        document.setFileSize(7L);
        document.setFilePath(source.toString());
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedAt(now);
        document.setProcessedAt(now);
        document.setUploadedBy(owner);
        return documentRepository.saveAndFlush(document);
    }

    private DocumentChunk persistChunk(Document document) {
        return chunkRepository.saveAndFlush(chunk(document, 0));
    }

    private DocumentChunk chunk(Document document, int index) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setChunkIndex(index);
        chunk.setTitle("Fixture");
        chunk.setContent("Fixture content");
        chunk.setStartPage(1);
        chunk.setEndPage(1);
        chunk.setWordCount(2);
        chunk.setCharacterCount(15);
        chunk.setCreatedAt(LocalDateTime.now());
        chunk.setChunkType(DocumentChunk.ChunkType.SIZE_BASED);
        return chunk;
    }
}
