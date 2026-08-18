package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.conversion.application.DocumentConversionService;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationService;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessService;
import uk.gegc.quizmaker.features.documentProcess.application.StructureService;
import uk.gegc.quizmaker.features.documentProcess.application.impl.NormalizedDocumentAccessServiceImpl;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("db-serial")
@DataJpaTest
@Import({
        NormalizedDocumentAccessServiceImpl.class,
        DocumentIngestionService.class,
        NormalizationService.class
})
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@DisplayName("Normalized document ownership with MySQL")
class NormalizedDocumentOwnershipMySqlIntegrationTest {

    @Autowired
    private NormalizedDocumentRepository documentRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private NormalizedDocumentAccessService documentAccessService;

    @MockitoBean
    private DocumentConversionService conversionService;
    @MockitoBean
    private MimeTypeDetector mimeTypeDetector;
    @MockitoBean
    private DocumentQueryService queryService;
    @MockitoBean
    private StructureService structureService;
    @MockitoBean
    private NormalizedDocumentAccessMetrics metrics;

    @Test
    @Transactional
    @DisplayName("persists the authenticated owner through the application write boundary")
    void applicationWritePersistsAuthenticatedOwner() {
        User owner = userRepository.saveAndFlush(newUser("writer"));

        NormalizedDocument created = documentAccessService.ingestFromText(
                owner.getUsername(),
                "private.txt",
                "en",
                "private text"
        );
        documentRepository.flush();
        entityManager.clear();

        NormalizedDocumentRepository.OwnerAuthorization authorization = documentRepository
                .findOwnerForAuthorization(created.getId())
                .orElseThrow();
        NormalizedDocument reloaded = documentRepository.findById(created.getId()).orElseThrow();
        assertThat(authorization.getOwnerUsername()).isEqualTo(owner.getUsername());
        assertThat(reloaded.getNormalizedText()).isEqualTo("private text");
    }

    @Test
    @Transactional
    @DisplayName("loads one owner decision without document content in one query with multiple rows present")
    void ownerAuthorizationQueryHasNoOwnerNPlusOne() {
        User owner = userRepository.saveAndFlush(newUser("bounded"));
        User otherOwner = userRepository.saveAndFlush(newUser("other"));
        NormalizedDocument saved = documentRepository.saveAndFlush(newDocument(owner));
        documentRepository.saveAndFlush(newDocument(owner));
        documentRepository.saveAndFlush(newDocument(otherOwner));
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Optional<NormalizedDocumentRepository.OwnerAuthorization> result =
                documentRepository.findOwnerForAuthorization(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getOwnerUsername()).isEqualTo(owner.getUsername());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityFetchCount()).isZero();
    }

    @Test
    @Transactional
    @DisplayName("keeps pre-migration rows nullable so the application can quarantine them")
    void legacyRowsRemainUnowned() {
        NormalizedDocument legacy = documentRepository.saveAndFlush(newDocument(null));
        entityManager.clear();

        NormalizedDocumentRepository.OwnerAuthorization authorization = documentRepository
                .findOwnerForAuthorization(legacy.getId())
                .orElseThrow();

        assertThat(authorization.getOwnerUsername()).isNull();
    }

    @Test
    @Transactional
    @DisplayName("production schema exposes the named owner index and SET NULL foreign key")
    void migrationCreatesExpectedIndexAndForeignKey() {
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'normalized_documents'
                  AND index_name = 'ix_normalized_documents_owner_id'
                  AND column_name = 'owner_id'
                """, Integer.class);
        String deleteRule = jdbcTemplate.queryForObject("""
                SELECT rc.delete_rule
                FROM information_schema.referential_constraints rc
                WHERE rc.constraint_schema = DATABASE()
                  AND rc.table_name = 'normalized_documents'
                  AND rc.constraint_name = 'fk_normalized_documents_owner'
                """, String.class);
        String nullable = jdbcTemplate.queryForObject("""
                SELECT c.is_nullable
                FROM information_schema.columns c
                WHERE c.table_schema = DATABASE()
                  AND c.table_name = 'normalized_documents'
                  AND c.column_name = 'owner_id'
                """, String.class);

        assertThat(indexCount).isEqualTo(1);
        assertThat(deleteRule).isEqualToIgnoringCase("SET NULL");
        assertThat(nullable).isEqualToIgnoringCase("YES");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("owner deletion waits for the authorization transaction and is visible to the next read")
    void ownerDeletionCannotRacePastAuthorizationLock() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Fixture fixture = transaction.execute(status -> {
            User owner = userRepository.saveAndFlush(newUser("concurrent"));
            NormalizedDocument document = documentRepository.saveAndFlush(newDocument(owner));
            return new Fixture(owner.getId(), document.getId());
        });
        assertThat(fixture).isNotNull();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        CountDownLatch deletionFinished = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = executor.submit(() -> transaction.executeWithoutResult(status -> {
                documentRepository.findOwnerForAuthorization(fixture.documentId()).orElseThrow();
                lockAcquired.countDown();
                await(releaseRead);
            }));
            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> deleter = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(status -> {
                        User owner = userRepository.findById(fixture.ownerId()).orElseThrow();
                        owner.setDeleted(true);
                        deletionStarted.countDown();
                        userRepository.saveAndFlush(owner);
                    });
                } finally {
                    deletionFinished.countDown();
                }
            });
            assertThat(deletionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(deletionFinished.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseRead.countDown();
            reader.get(5, TimeUnit.SECONDS);
            deleter.get(5, TimeUnit.SECONDS);

            Boolean deleted = transaction.execute(status -> documentRepository
                    .findOwnerForAuthorization(fixture.documentId())
                    .map(NormalizedDocumentRepository.OwnerAuthorization::getOwnerDeleted)
                    .orElseThrow());
            assertThat(deleted).isTrue();
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
            transaction.executeWithoutResult(status -> {
                documentRepository.deleteById(fixture.documentId());
                documentRepository.flush();
                userRepository.deleteById(fixture.ownerId());
                userRepository.flush();
            });
        }
    }

    private User newUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(prefix + "_" + suffix);
        user.setEmail(prefix + "_" + suffix + "@example.com");
        user.setHashedPassword("test-password");
        user.setActive(true);
        user.setDeleted(false);
        return user;
    }

    private NormalizedDocument newDocument(User owner) {
        NormalizedDocument document = new NormalizedDocument();
        document.setOwner(owner);
        document.setOriginalName("private.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setNormalizedText("private text");
        document.setCharCount(12);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        return document;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test", interrupted);
        }
    }

    private record Fixture(UUID ownerId, UUID documentId) {
    }
}
