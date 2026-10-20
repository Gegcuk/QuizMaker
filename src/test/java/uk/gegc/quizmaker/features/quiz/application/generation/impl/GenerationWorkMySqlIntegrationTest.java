package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import({GenerationWorkServiceImpl.class, GenerationWorkExecutorImpl.class,
        uk.gegc.quizmaker.features.quiz.config.GenerationLeaseSchedulingConfig.class,
        QuizGenerationCheckpointServiceImpl.class, QuizGenerationCheckpointCodec.class,
        QuizGenerationCoverageServiceImpl.class, ProviderUsageServiceImpl.class,
        uk.gegc.quizmaker.features.quiz.application.impl.QuizGenerationJobServiceImpl.class,
        GenerationWorkMySqlIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("db-serial")
class GenerationWorkMySqlIntegrationTest {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));
        void advance(long seconds) { time.updateAndGet(t -> t.plusSeconds(seconds)); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean(name = "systemClock") MutableClock clock() { return new MutableClock(); }
        @Bean QuizJobProperties properties() { return new QuizJobProperties(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
    }
    @Autowired GenerationWorkService service;
    @Autowired GenerationWorkExecutor worker;
    @Autowired QuizGenerationCheckpointService checkpoints;
    @Autowired QuizGenerationCoverageService coverage;
    @Autowired ProviderUsageService providerUsage;
    @Autowired uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService jobService;
    @Autowired QuizGenerationProviderUsageRepository usageRecords;
    @Autowired QuizGenerationWorkRepository works;
    @Autowired QuizGenerationJobRepository jobs;
    @Autowired QuizGenerationOutputCheckpointRepository outputs;
    @Autowired QuizGenerationCoverageRepository coverages;
    @Autowired DocumentRepository documents;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired MutableClock clock;
    @MockitoBean InternalBillingService billing;
    @MockitoBean uk.gegc.quizmaker.features.billing.application.BillingService publicBilling;
    @MockitoBean AiQuizGenerationService ai;
    final List<UUID> userIds = new ArrayList<>();
    final List<UUID> documentIds = new ArrayList<>();
    final List<UUID> jobIds = new ArrayList<>();

    @BeforeEach void resetClock() {
        clock.time.set(Instant.parse("2026-10-20T12:00:00Z"));
    }
    @AfterEach void cleanup() {
        for (UUID id : jobIds) {
            transactions.executeWithoutResult(tx -> entityManager.createQuery(
                    "delete from QuizGenerationProviderUsage u where u.jobId = :jobId")
                    .setParameter("jobId", id).executeUpdate());
            works.deleteById(id);
            outputs.deleteById(id);
            coverages.deleteById(id);
            jobs.deleteById(id);
        }
        documentIds.forEach(documents::deleteById);
        userIds.forEach(users::deleteById);
    }

    @Test void enrollmentAndJobUpdateRollbackInsideTheCallingTransaction() {
        QuizGenerationJob job = fixture(false, false);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            service.enroll(job);
            job.setCurrentChunk("uncommitted acceptance");
            jobs.saveAndFlush(job);
            throw new IllegalStateException("acceptance failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(works.existsById(job.getId())).isFalse();
        assertThat(jobs.findById(job.getId()).orElseThrow().getCurrentChunk()).isNotEqualTo("uncommitted acceptance");
        verifyNoInteractions(ai);
    }

    @Test void inlineAfterCommitDispatchUsesFreshTransactionsAndPersistsItsCheckpoint() {
        QuizGenerationJob job = fixture(true, false);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            checkpoints.save(job.getId(), questions());
            return null;
        }).when(ai).generateQuizFromDocumentAsync(eq(job.getId()), any());
        transactions.executeWithoutResult(tx -> {
            // Defensive service-boundary check with acceptance resources still bound.
            // Production dispatch now always uses its dedicated worker executor.
            works.findById(job.getId()).orElseThrow();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { worker.execute(job.getId()); }
                    });
        });
        assertThat(outputs.existsById(job.getId())).isTrue();
        assertThat(works.findById(job.getId()).orElseThrow().getState()).isEqualTo(QuizGenerationWork.State.DONE);
        verify(ai).generateQuizFromDocumentAsync(eq(job.getId()), any());
    }

    @Test void legacyActivationCleanupCannotCancelRecoverableDurableWork() {
        QuizGenerationJob durable = fixture(true, false), legacy = fixture(false, false);
        transactions.executeWithoutResult(tx -> {
            jobs.findByIdForUpdate(durable.getId()).orElseThrow().setStartedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
            jobs.findByIdForUpdate(legacy.getId()).orElseThrow().setStartedAt(LocalDateTime.of(2020, 1, 1, 0, 0));
        });
        jobService.cleanupStalePendingJobs();
        assertThat(jobs.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(jobs.findById(durable.getId()).orElseThrow().getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(jobService.findAndCancelStaleJobForUser(durable.getUser().getUsername())).isEmpty();
        assertThat(service.claim(durable.getId())).isPresent();
        verifyNoInteractions(billing, publicBilling);
    }

    @Test void legacyTimeoutScanIsBoundedAndLoadsMultipleOwnersInOneQuery() {
        List<UUID> legacyIds = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            legacyIds.add(fixture(false, false).getId());
            fixture(true, false);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        transactions.executeWithoutResult(tx -> {
            var candidates = jobs.findLegacyPendingJobsBefore(null, LocalDateTime.now(clock).plusDays(1),
                    org.springframework.data.domain.PageRequest.of(0, 2));
            assertThat(candidates).hasSize(2).allSatisfy(job -> {
                assertThat(legacyIds).contains(job.getId());
                assertThat(job.getUser().getUsername()).startsWith("work-");
            });
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    @Test void lostDispatchIsDiscoveredAndOnlyOneConcurrentWorkerCanClaim() throws Exception {
        QuizGenerationJob job = fixture(true, false);
        assertThat(service.findDue()).containsExactly(job.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Optional<GenerationWorkClaim>> claim = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return service.claim(job.getId());
            };
            Future<Optional<GenerationWorkClaim>> first = pool.submit(claim);
            Future<Optional<GenerationWorkClaim>> second = pool.submit(claim);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .filteredOn(Optional::isPresent).hasSize(1);
            assertThat(works.findById(job.getId()).orElseThrow().getExecutions()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void exactLeaseBoundaryAllowsOneRecoveryAndFencesFormerOwner() {
        QuizGenerationJob job = fixture(true, false);
        GenerationWorkClaim former = service.claim(job.getId()).orElseThrow();
        clock.advance(120);
        GenerationWorkClaim current = service.claim(job.getId()).orElseThrow();
        assertThat(current.fence()).isGreaterThan(former.fence());
        assertThatThrownBy(() -> service.protect(former, () -> {
            jobs.incrementCompletedTasks(job.getId(), 1, "stale");
            return null;
        })).isInstanceOf(GenerationLeaseLostException.class);
        GenerationWorkerContext.run(former, service, () -> {
            assertThatThrownBy(() -> checkpoints.save(job.getId(), questions()))
                    .isInstanceOf(GenerationLeaseLostException.class);
            assertThatThrownBy(() -> coverage.saveDecision(job.getId(), coverageSnapshot()))
                    .isInstanceOf(GenerationLeaseLostException.class);
        });
        assertThat(outputs.existsById(job.getId())).isFalse();
        assertThat(coverages.existsById(job.getId())).isFalse();
        assertThat(jobs.findById(job.getId()).orElseThrow().getCompletedTasks()).isZero();
        assertThat(service.protect(current, () -> "current owner")).isEqualTo("current owner");
    }

    @Test void aFencedWriteAndTakeoverAreSerializedByTheJobLock() throws Exception {
        QuizGenerationJob job = fixture(true, false);
        GenerationWorkClaim old = service.claim(job.getId()).orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> writing = pool.submit(() -> service.protect(old, () -> {
                held.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            clock.advance(120);
            Future<?> takeover = pool.submit(() -> service.claim(job.getId()));
            assertThatThrownBy(() -> takeover.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            writing.get(5, TimeUnit.SECONDS);
            takeover.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void heartbeatExtendsOnlyTheCurrentUnexpiredLease() {
        QuizGenerationJob job = fixture(true, false);
        GenerationWorkClaim first = service.claim(job.getId()).orElseThrow();
        clock.advance(30);
        service.heartbeat(first);
        assertThat(works.findById(job.getId()).orElseThrow().getLeaseUntil())
                .isEqualTo(LocalDateTime.now(clock).plusSeconds(120));
        clock.advance(120);
        service.heartbeat(first);
        assertThatThrownBy(() -> service.protect(first, () -> null)).isInstanceOf(GenerationLeaseLostException.class);
        GenerationWorkClaim second = service.claim(job.getId()).orElseThrow();
        service.heartbeat(first);
        assertThat(service.protect(second, () -> true)).isTrue();
    }

    @Test void secondInterruptedExecutionFailsAndReleasesExactlyOnce() {
        QuizGenerationJob job = fixture(true, true);
        service.claim(job.getId()).orElseThrow();
        clock.advance(120);
        service.claim(job.getId()).orElseThrow();
        clock.advance(120);
        assertThat(service.claim(job.getId())).isEmpty();
        assertThat(service.claim(job.getId())).isEmpty();
        QuizGenerationJob failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.getBillingState()).isEqualTo(BillingState.RELEASED);
        assertThat(failed.getCompletedAt()).isEqualTo(LocalDateTime.now(clock));
        verify(billing, times(1)).release(eq(job.getBillingReservationId()), anyString(),
                eq(job.getId().toString()), eq("quiz:" + job.getId() + ":release"));
        verifyNoInteractions(ai);
    }

    @Test void changedSourceFailsWithoutCallingProviderOrHashingDocument() {
        QuizGenerationJob job = fixture(true, false);
        service.claim(job.getId()).orElseThrow();
        transactions.executeWithoutResult(tx -> {
            Document source = documents.findByIdForDeletion(job.getDocumentId()).orElseThrow();
            source.setContentRevision(source.getContentRevision() + 1);
        });
        clock.advance(120);
        worker.execute(job.getId());
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorMessage()).contains("source changed");
        verifyNoInteractions(ai);
    }

    @Test void wrongOwnerOrDeletedSourceNeverDispatches() {
        QuizGenerationJob wrongOwner = fixture(true, false);
        transactions.executeWithoutResult(tx -> {
            Document source = documents.findByIdForDeletion(wrongOwner.getDocumentId()).orElseThrow();
            source.setUploadedBy(newUser());
        });
        worker.execute(wrongOwner.getId());
        QuizGenerationJob removed = fixture(true, false);
        documents.deleteById(removed.getDocumentId());
        documentIds.remove(removed.getDocumentId());
        worker.execute(removed.getId());
        assertThat(jobs.findById(wrongOwner.getId()).orElseThrow().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(jobs.findById(removed.getId()).orElseThrow().getStatus()).isEqualTo(GenerationStatus.FAILED);
        verifyNoInteractions(ai);
    }

    @Test void expiredReservationIsNotRenewedOrDispatched() {
        QuizGenerationJob job = fixture(true, true);
        clock.advance(3600);
        worker.execute(job.getId());
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorMessage()).contains("reservation expired");
        verify(billing, never()).renewReservationLease(any(), any(), any());
        verifyNoInteractions(ai);
    }

    @Test void checkpointAfterCrashSkipsProviderAndPreservesExistingFinalizationInput() {
        QuizGenerationJob job = fixture(true, false);
        GenerationWorkClaim claim = service.claim(job.getId()).orElseThrow();
        GenerationWorkerContext.run(claim, service, () -> checkpoints.save(job.getId(), questions()));
        clock.advance(120);
        worker.execute(job.getId());
        assertThat(checkpoints.getRequired(job.getId()).questionCount()).isEqualTo(1);
        assertThat(works.findById(job.getId()).orElseThrow().getState()).isEqualTo(QuizGenerationWork.State.DONE);
        verifyNoInteractions(ai);
    }

    @Test void aFreshExecutorRecoversFromPersistedRequestWithAFakeProviderPort() {
        QuizGenerationJob job = fixture(true, false);
        service.claim(job.getId()).orElseThrow();
        clock.advance(120);
        doAnswer(call -> {
            checkpoints.save(job.getId(), questions());
            return null;
        }).when(ai).generateQuizFromDocumentAsync(eq(job.getId()), any());
        var restarted = new GenerationWorkExecutorImpl(service, ai, new ObjectMapper().findAndRegisterModules(), clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        restarted.execute(job.getId());
        restarted.execute(job.getId());
        verify(ai, times(1)).generateQuizFromDocumentAsync(eq(job.getId()), any());
        assertThat(outputs.existsById(job.getId())).isTrue();
        assertThat(works.findById(job.getId()).orElseThrow().getExecutions()).isEqualTo(2);
    }

    @Test void cancellationPreventsLateProgressCoverageAndOutputWithoutReleasingAgain() {
        QuizGenerationJob job = fixture(true, false);
        GenerationWorkClaim claim = service.claim(job.getId()).orElseThrow();
        transactions.executeWithoutResult(tx -> jobs.findByIdForUpdate(job.getId()).orElseThrow()
                .setStatus(GenerationStatus.CANCELLED));
        GenerationWorkerContext.run(claim, service, () -> {
            assertThatThrownBy(() -> GenerationWorkerContext.write(job.getId(), () ->
                    jobs.incrementCompletedTasks(job.getId(), 1, "late")))
                    .isInstanceOf(GenerationLeaseLostException.class);
            assertThatThrownBy(() -> checkpoints.save(job.getId(), questions())).isInstanceOf(GenerationLeaseLostException.class);
            assertThatThrownBy(() -> coverage.saveDecision(job.getId(), coverageSnapshot())).isInstanceOf(GenerationLeaseLostException.class);
        });
        service.finished(claim);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(GenerationStatus.CANCELLED);
        verifyNoInteractions(billing);
    }

    @Test void interruptedCoverageIsReplacedOnlyBeforeCheckpointAndProgressRemainsMonotonic() {
        QuizGenerationJob job = fixture(true, false);
        GenerationWorkClaim old = service.claim(job.getId()).orElseThrow();
        GenerationWorkerContext.run(old, service, () -> coverage.saveDecision(job.getId(), coverageSnapshot()));
        transactions.executeWithoutResult(tx -> {
            var managed = jobs.findByIdForUpdate(job.getId()).orElseThrow();
            managed.setCompletedTasks(1);
            managed.setProgressPercentage(80.0);
        });
        clock.advance(120);
        var current = service.claim(job.getId()).orElseThrow();
        assertThat(coverages.existsById(job.getId())).isFalse();
        assertThat(jobs.findById(job.getId()).orElseThrow().getProgressPercentage()).isEqualTo(99.0);
        GenerationWorkerContext.run(current, service, () -> coverage.saveDecision(job.getId(), coverageSnapshot()));
        assertThat(coverage.findByJobIds(List.of(job.getId()))).containsKey(job.getId());
    }

    @Test void releaseFailureDoesNotRollBackTerminalFailureOrRestartTheProvider() {
        QuizGenerationJob job = fixture(true, true);
        GenerationWorkClaim claim = service.claim(job.getId()).orElseThrow();
        doThrow(new IllegalStateException("local fake billing outage")).when(billing)
                .release(any(), anyString(), anyString(), anyString());
        service.failed(claim);
        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.getBillingState()).isEqualTo(BillingState.RESERVED);
        assertThat(failed.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.FAILED);
        assertThat(failed.getLastBillingError()).contains("pending");
        assertThat(works.findById(job.getId()).orElseThrow().getState()).isEqualTo(QuizGenerationWork.State.DONE);
        clock.advance(120);
        worker.execute(job.getId());
        verifyNoInteractions(ai);
    }

    @Test void aProviderStartChecksTheFenceInsideItsOwnTransactionAndLateUsageStillReconciles() {
        QuizGenerationJob job = fixture(true, false);
        GenerationWorkClaim former = service.claim(job.getId()).orElseThrow();
        UUID startedAttempt = UUID.randomUUID();
        GenerationWorkerContext.run(former, service, () -> providerUsage.recordStarted(job.getId(), startedAttempt));
        // A check before the accounting transaction is insufficient: takeover can win in between.
        service.protect(former, () -> null);
        clock.advance(120);
        service.claim(job.getId()).orElseThrow();
        UUID staleAttempt = UUID.randomUUID();
        GenerationWorkerContext.run(former, service, () -> {
            assertThatThrownBy(() -> providerUsage.recordStarted(job.getId(), staleAttempt))
                    .isInstanceOf(GenerationLeaseLostException.class);
            assertThat(providerUsage.recordReported(job.getId(), startedAttempt, 37L))
                    .isEqualTo(ProviderUsageRecordResult.RECORDED);
        });
        assertThat(usageRecords.countByJobId(job.getId())).isEqualTo(1);
        assertThat(usageRecords.findByJobIdAndProviderAttemptId(job.getId(), staleAttempt)).isEmpty();
        assertThat(jobs.findById(job.getId()).orElseThrow().getProviderLlmTokens()).isEqualTo(37L);
    }

    @Test void scansAreBoundedScalarQueriesWithMultipleParentsAndExcludeLegacyWork() {
        fixture(false, false);
        for (int n = 0; n < 55; n++) fixture(true, false);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<UUID> due = service.findDue();
        assertThat(due).hasSize(50);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    @Test void sourceSnapshotsLoadMultipleChunksWithoutPerChunkQueries() {
        List<GenerationWorkClaim> claims = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            QuizGenerationJob job = fixture(true, false);
            transactions.executeWithoutResult(tx -> {
                Document source = documents.findByIdForDeletion(job.getDocumentId()).orElseThrow();
                for (int index = 0; index < 3; index++) {
                    var chunk = new uk.gegc.quizmaker.features.document.domain.model.DocumentChunk();
                    chunk.setDocument(source); chunk.setChunkIndex(index); chunk.setTitle("Synthetic chunk");
                    chunk.setContent("Synthetic content"); chunk.setStartPage(1); chunk.setEndPage(1);
                    chunk.setWordCount(2); chunk.setCharacterCount(17); chunk.setCreatedAt(LocalDateTime.now(clock));
                    source.getChunks().add(chunk);
                }
                documents.saveAndFlush(source);
                assertThat(documents.findContentRevisionForUpdate(source.getId())).isZero();
            });
            claims.add(service.claim(job.getId()).orElseThrow());
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        for (var claim : claims) {
            Document snapshot = service.protect(claim, () -> documents.findByIdWithChunksAndUser(claim.documentId()).orElseThrow());
            assertThat(snapshot.getChunks()).hasSize(3);
            assertThat(snapshot.getUploadedBy().getUsername()).startsWith("work-");
        }
        // One job lock, work lookup, checkpoint probe, source lock, and eager snapshot
        // per execution, plus at most the existing unique-username owner resolution.
        assertThat(statistics.getPrepareStatementCount()).isBetween(15L, 18L);
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    @Test void fifoUsesAcceptanceSequenceEvenWhenAllTimestampsMatchAndCapacityIsGlobal() throws Exception {
        List<UUID> ordered = new ArrayList<>();
        for (int i = 0; i < 6; i++) ordered.add(fixture(true, false).getId());
        assertThat(service.claim(ordered.get(1))).isEmpty();
        assertThat(service.findDue()).containsExactlyElementsOf(ordered);
        ExecutorService instances = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<GenerationWorkClaim>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(instances.submit(() -> service.claimNext()));
            List<GenerationWorkClaim> admitted = new ArrayList<>();
            for (var future : results) future.get(10, TimeUnit.SECONDS).ifPresent(admitted::add);
            assertThat(admitted).hasSize(4);
            assertThat(admitted).extracting(GenerationWorkClaim::jobId)
                    .containsExactlyInAnyOrderElementsOf(ordered.subList(0, 4));
            assertThat(service.claimNext()).isEmpty();
            service.failed(admitted.get(0));
            assertThat(service.claimNext()).get().extracting(GenerationWorkClaim::jobId).isEqualTo(ordered.get(4));
        } finally { instances.shutdownNow(); }
    }

    @Test void deadlineStartsAtFirstExecutionAndSurvivesRecoveryAtTheExactBoundary() {
        QuizGenerationJob job = fixture(true, true);
        clock.advance(480); // Queue wait is deliberately longer than five minutes.
        var first = service.claimNext().orElseThrow();
        assertThat(first.deadline()).isEqualTo(clock.instant().plusSeconds(300));
        clock.advance(120);
        var resumed = service.claimNext().orElseThrow();
        assertThat(resumed.deadline()).isEqualTo(first.deadline());
        clock.advance(180);
        worker.execute(job.getId());
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorMessage()).contains("deadline exceeded");
        assertThat(jobs.findById(job.getId()).orElseThrow().getBillingState()).isEqualTo(BillingState.RELEASED);
        verifyNoInteractions(ai);
    }

    @Test void existingStartedWorkRemainsUnboundedAndCompletedOutputCanFinalizeAfterDeadline() {
        QuizGenerationJob legacy = fixture(true, false);
        transactions.executeWithoutResult(tx -> entityManager.createNativeQuery(
                "update quiz_generation_work set deadline_enabled = false where job_id = :id")
                .setParameter("id", legacy.getId()).executeUpdate());
        var old = service.claimNext().orElseThrow();
        assertThat(old.deadline()).isNull();
        clock.advance(600);
        var resumed = service.claimNext().orElseThrow();
        assertThat(resumed.deadline()).isNull();
        service.failed(resumed);
        QuizGenerationJob fresh = fixture(true, false);
        var claim = service.claim(fresh.getId()).orElseThrow();
        for (int i = 0; i < 11; i++) { clock.advance(30); service.heartbeat(claim); }
        GenerationWorkerContext.run(claim, service, clock, () -> checkpoints.save(fresh.getId(), questions()));
        assertThat(outputs.existsById(fresh.getId())).isTrue();
    }

    private QuizGenerationJob fixture(boolean enroll, boolean paid) {
        return transactions.execute(tx -> {
            User owner = newUser();
            Document doc = new Document();
            doc.setUploadedBy(owner);
            doc.setOriginalFilename("synthetic.txt"); doc.setContentType("text/plain");
            doc.setFilePath("/synthetic/never-read"); doc.setFileSize(200L);
            doc.setStatus(Document.DocumentStatus.PROCESSED);
            documents.saveAndFlush(doc); documentIds.add(doc.getId());
            QuizGenerationJob job = new QuizGenerationJob();
            job.setUser(owner); job.setDocumentId(doc.getId());
            job.setStatus(GenerationStatus.PENDING); job.setStartedAt(LocalDateTime.now(clock));
            job.setRequestData("{\"documentId\":\"" + doc.getId() + "\",\"questionsPerType\":{\"TRUE_FALSE\":1},\"difficulty\":\"EASY\",\"language\":\"en\"}");
            job.setTotalChunks(1); job.setTotalTasks(1);
            if (paid) {
                job.setBillingReservationId(UUID.randomUUID()); job.setBillingState(BillingState.RESERVED);
                job.setReservationExpiresAt(LocalDateTime.now(clock).plusMinutes(30));
            }
            jobs.saveAndFlush(job); jobIds.add(job.getId());
            if (enroll) service.enroll(job);
            return job;
        });
    }
    private User newUser() {
        User user = new User();
        user.setUsername("work-" + UUID.randomUUID());
        user.setEmail(user.getUsername() + "@example.test"); user.setHashedPassword("synthetic-hash");
        users.saveAndFlush(user); userIds.add(user.getId()); return user;
    }
    private Map<Integer, List<Question>> questions() {
        Question question = new Question();
        question.setType(QuestionType.TRUE_FALSE); question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("A synthetic persisted question");
        question.setContent("{\"answer\":true}");
        return Map.of(0, List.of(question));
    }
    private GenerationCoverageSnapshot coverageSnapshot() {
        return new GenerationCoverageSnapshot(GenerationCoverageOutcome.COMPLETE, 80, 1, 1, 0, 0,
                List.of(new GenerationCoverageSnapshot.TypeCoverage(QuestionType.TRUE_FALSE, 1, 1, 0)));
    }
}
