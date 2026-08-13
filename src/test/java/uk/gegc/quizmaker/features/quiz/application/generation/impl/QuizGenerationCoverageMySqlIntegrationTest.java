package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageException;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageService;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationCoverageRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import({
        QuizGenerationCoverageServiceImpl.class,
        QuizGenerationCoverageMySqlIntegrationTest.TestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("db-serial")
@DisplayName("Quiz generation coverage with MySQL")
class QuizGenerationCoverageMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean(name = "systemClock")
        Clock systemClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private QuizGenerationCoverageService coverageService;

    @Autowired
    private QuizGenerationCoverageRepository coverageRepository;

    @Autowired
    private QuizGenerationJobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private final List<UUID> jobIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private QuizGenerationJob job;

    @BeforeEach
    void setUp() {
        job = persistProcessingJob();
    }

    @AfterEach
    void cleanUp() {
        coverageRepository.deleteAllInBatch();
        for (UUID jobId : jobIds) {
            jobRepository.deleteById(jobId);
        }
        for (UUID userId : userIds) {
            userRepository.deleteById(userId);
        }
    }

    @Test
    @DisplayName("Coverage survives a fresh persistence context before terminal handling")
    void coverageIsDurableBeforeTerminalHandling() {
        coverageService.saveDecision(job.getId(), partialSnapshot());
        entityManager.clear();

        Map<UUID, GenerationCoverageSnapshot> reloaded =
                coverageService.findByJobIds(List.of(job.getId()));

        assertThat(reloaded.get(job.getId())).isEqualTo(partialSnapshot());
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("Coverage REQUIRES_NEW commit survives a caller transaction rollback")
    void coverageSurvivesCallerRollback() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            coverageService.saveDecision(job.getId(), failedSnapshot());
            throw new IllegalStateException("simulate threshold failure handling");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("threshold failure");

        assertThat(coverageService.findByJobIds(List.of(job.getId())).get(job.getId()))
                .isEqualTo(failedSnapshot());
    }

    @Test
    @DisplayName("Cancellation racing reconciliation leaves a cancelled job with either no fact or one complete fact")
    void cancellationRaceCannotPersistPartialOrReplaceableCoverage() throws Exception {
        List<Throwable> failures = runConcurrently(
                () -> coverageService.saveDecision(job.getId(), partialSnapshot()),
                () -> transactionTemplate.executeWithoutResult(status -> {
                    QuizGenerationJob lockedJob = jobRepository.findByIdForUpdate(job.getId()).orElseThrow();
                    lockedJob.setStatus(GenerationStatus.CANCELLED);
                    lockedJob.markFinalizationCancelled(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
                    jobRepository.saveAndFlush(lockedJob);
                })
        );

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(GenerationStatus.CANCELLED);
        Map<UUID, GenerationCoverageSnapshot> persisted =
                coverageService.findByJobIds(List.of(job.getId()));
        if (failures.isEmpty()) {
            assertThat(persisted.get(job.getId())).isEqualTo(partialSnapshot());
        } else {
            assertThat(failures).singleElement().isInstanceOf(QuizGenerationCoverageException.class);
            assertThat(persisted).doesNotContainKey(job.getId());
        }
    }

    @Test
    @DisplayName("Concurrent identical decisions create one economic fact and one idempotent replay")
    void concurrentIdenticalDecisionsAreIdempotent() throws Exception {
        List<Throwable> failures = runConcurrently(
                () -> coverageService.saveDecision(job.getId(), partialSnapshot()),
                () -> coverageService.saveDecision(job.getId(), partialSnapshot())
        );

        assertThat(failures).isEmpty();
        assertThat(coverageRepository.count()).isEqualTo(1L);
        assertThat(coverageService.findByJobIds(List.of(job.getId())).get(job.getId()))
                .isEqualTo(partialSnapshot());
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "saved").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "duplicate").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Concurrent conflicting decisions preserve the winner and fail the other writer")
    void concurrentConflictingDecisionsFailClosed() throws Exception {
        List<Throwable> failures = runConcurrently(
                () -> coverageService.saveDecision(job.getId(), partialSnapshot()),
                () -> coverageService.saveDecision(job.getId(), failedSnapshot())
        );

        assertThat(failures).singleElement().isInstanceOf(QuizGenerationCoverageException.class);
        GenerationCoverageSnapshot persisted =
                coverageService.findByJobIds(List.of(job.getId())).get(job.getId());
        assertThat(persisted).isIn(partialSnapshot(), failedSnapshot());
        assertThat(coverageRepository.count()).isEqualTo(1L);
        assertThat(meterRegistry.counter(
                "quiz.generation.coverage.operations", "outcome", "conflict").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("One batch query loads aggregate and per-type coverage for an entire status page")
    void batchReadHasNoPerJobNPlusOneQueries() {
        QuizGenerationJob second = persistProcessingJob();
        QuizGenerationJob third = persistProcessingJob();
        coverageService.saveDecision(job.getId(), partialSnapshot());
        coverageService.saveDecision(second.getId(), completeSnapshot());
        coverageService.saveDecision(third.getId(), failedSnapshot());
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        Map<UUID, GenerationCoverageSnapshot> result = coverageService.findByJobIds(
                List.of(job.getId(), second.getId(), third.getId()));

        assertThat(result).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    private List<Throwable> runConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Throwable> wrappedFirst = concurrentCall(first, ready, start);
            Callable<Throwable> wrappedSecond = concurrentCall(second, ready, start);
            Future<Throwable> firstResult = executor.submit(wrappedFirst);
            Future<Throwable> secondResult = executor.submit(wrappedSecond);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return java.util.stream.Stream.of(
                            firstResult.get(10, TimeUnit.SECONDS),
                            secondResult.get(10, TimeUnit.SECONDS)
                    )
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Callable<Throwable> concurrentCall(
            Runnable action,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                return new IllegalStateException("concurrent test start timed out");
            }
            try {
                action.run();
                return null;
            } catch (Throwable throwable) {
                return throwable;
            }
        };
    }

    private QuizGenerationJob persistProcessingJob() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = new User();
        user.setUsername("coverage_" + suffix);
        user.setEmail("coverage-" + suffix + "@example.test");
        user.setHashedPassword("not-used");
        user.setActive(true);
        user = userRepository.saveAndFlush(user);
        userIds.add(user.getId());

        QuizGenerationJob persisted = new QuizGenerationJob();
        persisted.setUser(user);
        persisted.setDocumentId(UUID.randomUUID());
        persisted.setStatus(GenerationStatus.PROCESSING);
        persisted.setRequestData("{}");
        persisted = jobRepository.saveAndFlush(persisted);
        jobIds.add(persisted.getId());
        return persisted;
    }

    private GenerationCoverageSnapshot partialSnapshot() {
        return new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.PARTIAL,
                80,
                10,
                9,
                1,
                2,
                List.of(
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.MCQ_SINGLE, 5, 5, 0),
                        new GenerationCoverageSnapshot.TypeCoverage(QuestionType.FILL_GAP, 5, 4, 1)
                )
        );
    }

    private GenerationCoverageSnapshot failedSnapshot() {
        return new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.FAILED_THRESHOLD,
                80,
                10,
                8,
                2,
                0,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(
                        QuestionType.MCQ_SINGLE, 10, 8, 2))
        );
    }

    private GenerationCoverageSnapshot completeSnapshot() {
        return new GenerationCoverageSnapshot(
                GenerationCoverageOutcome.COMPLETE,
                80,
                5,
                5,
                0,
                1,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(
                        QuestionType.OPEN, 5, 5, 0))
        );
    }
}
