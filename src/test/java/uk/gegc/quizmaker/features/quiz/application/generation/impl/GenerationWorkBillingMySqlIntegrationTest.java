package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.*;
import uk.gegc.quizmaker.features.billing.application.impl.*;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.mapping.*;
import uk.gegc.quizmaker.features.billing.infra.repository.*;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real local ledger transactions; only AI, assembly, and unrelated acceptance ports are fakes. */
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create",
        "quiz.jobs.finalization.recovery-grace-seconds=0",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import({GenerationWorkServiceImpl.class, GenerationWorkExecutorImpl.class,
        QuizGenerationFacadeImpl.class, QuizGenerationCheckpointServiceImpl.class,
        QuizGenerationCheckpointCodec.class, ProviderUsageServiceImpl.class,
        BillingServiceImpl.class, InternalBillingServiceImpl.class, GenerationTariffServiceImpl.class,
        uk.gegc.quizmaker.features.quiz.config.GenerationLeaseSchedulingConfig.class,
        BalanceMapperImpl.class, ReservationMapperImpl.class, TokenTransactionMapperImpl.class,
        GenerationWorkBillingMySqlIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("db-serial")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GenerationWorkBillingMySqlIntegrationTest {
    static class TestClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(Instant.now());
        void advance(long seconds) { time.updateAndGet(t -> t.plusSeconds(seconds)); }
        public ZoneId getZone() { return ZoneId.systemDefault(); }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return time.get(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean TestClock systemClock() { return new TestClock(); }
        @Bean QuizJobProperties properties() {
            var properties = new QuizJobProperties();
            properties.getFinalization().setRecoveryGraceSeconds(0);
            return properties;
        }
        @Bean BillingProperties billingProperties() {
            var properties = new BillingProperties();
            properties.setReservationSweeperMs(Duration.ofDays(1).toMillis());
            return properties;
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean FeatureFlags flags() { return new FeatureFlags(); }
    }

    @Autowired GenerationWorkService work;
    @Autowired GenerationWorkExecutor worker;
    @Autowired QuizGenerationFacade facade;
    @MockitoSpyBean QuizGenerationCheckpointService checkpoints;
    @Autowired ProviderUsageService usage;
    @MockitoSpyBean InternalBillingService billing;
    @Autowired BillingService billingService;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TestClock clock;
    @Autowired FeatureFlags flags;
    @Autowired QuizGenerationJobRepository jobs;
    @Autowired QuizGenerationWorkRepository works;
    @Autowired QuizGenerationOutputCheckpointRepository outputs;
    @Autowired QuizRepository quizzes;
    @Autowired CategoryRepository categories;
    @Autowired UserRepository users;
    @Autowired DocumentRepository documents;
    @Autowired BalanceRepository balances;
    @Autowired ReservationRepository reservations;
    @Autowired TokenTransactionRepository ledger;
    @Autowired QuizGenerationProviderUsageRepository usageRecords;
    @MockitoBean AiQuizGenerationService ai;
    @MockitoBean QuizAssemblyService assembly;
    @MockitoBean QuizGenerationJobService jobService;
    @MockitoBean DocumentProcessingService documentProcessing;
    @MockitoBean EstimationService estimation;
    @MockitoBean QuizGenerationIdempotencyService idempotency;
    @MockitoBean QuizGenerationRequestCanonicalizer canonicalizer;
    @MockitoBean BillingMetricsService billingMetrics;
    final List<QuizGenerationJob> fixtures = new ArrayList<>();
    final List<UUID> categoryIds = new ArrayList<>();

    @BeforeEach void resetClock() { clock.time.set(Instant.now()); }

    @AfterEach void cleanup() {
        for (var job : fixtures) {
            usageRecords.findAll().stream().filter(u -> u.getJobId().equals(job.getId()))
                    .forEach(usageRecords::delete);
            quizzes.deleteAllInBatch(quizzes.findByCreatorId(job.getUser().getId()));
            ledger.findByUserId(job.getUser().getId()).forEach(ledger::delete);
            outputs.deleteById(job.getId());
            works.deleteById(job.getId());
            jobs.deleteById(job.getId());
            reservations.deleteById(job.getBillingReservationId());
            balances.deleteById(job.getUser().getId());
            documents.deleteById(job.getDocumentId());
            users.deleteById(job.getUser().getId());
        }
        categoryIds.forEach(categories::deleteById);
    }

    @Test void aReleaseTransactionRollbackCannotResurrectGenerationAndRecoveryReleasesOnce() {
        var job = fixture();
        var claim = work.claim(job.getId()).orElseThrow();
        doThrow(new IllegalStateException("local injected billing transaction failure")).doNothing()
                .when(billingMetrics).incrementTokensReleased(eq(job.getUser().getId()), anyLong(), anyString());
        work.failed(claim);
        var failed = jobs.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(failed.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.FAILED);
        assertThat(failed.getBillingState()).isEqualTo(BillingState.RESERVED);
        assertThat(works.findById(job.getId()).orElseThrow().getState()).isEqualTo(QuizGenerationWork.State.DONE);
        assertThat(reservations.findById(job.getBillingReservationId()).orElseThrow().getState())
                .isEqualTo(ReservationState.ACTIVE);
        assertBalance(job, 996, 4);
        assertThat(ledger.findByUserId(job.getUser().getId())).extracting(TokenTransaction::getType)
                .containsExactly(TokenTransactionType.RESERVE);
        clock.advance(120);
        worker.execute(job.getId());
        facade.recoverStalledQuizGenerationFinalizations();
        facade.recoverStalledQuizGenerationFinalizations();
        assertReleasedOnce(job);
        verifyNoInteractions(ai, assembly);
    }

    @Test void reservationRenewalAfterTheRecoveryScanPreventsStaleFailureAndRelease() throws Exception {
        var job = fixture();
        var claim = work.claim(job.getId()).orElseThrow();
        transactions.executeWithoutResult(tx -> {
            var expiredAt = LocalDateTime.now().minusMinutes(1);
            jobs.findByIdForUpdate(job.getId()).orElseThrow().setReservationExpiresAt(expiredAt);
            reservations.findById(job.getBillingReservationId()).orElseThrow().setExpiresAt(expiredAt);
        });
        CountDownLatch scanned = new CountDownLatch(1), resume = new CountDownLatch(1);
        doAnswer(call -> {
            var batch = (QuizGenerationCheckpointService.RecoveryBatch) call.callRealMethod();
            assertThat(batch.expiredUncheckpointed()).contains(job.getId());
            scanned.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return batch;
        }).when(checkpoints).findRecoveryBatch(anyInt(), anyInt());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> recovery = executor.submit(facade::recoverStalledQuizGenerationFinalizations);
            assertThat(scanned.await(10, TimeUnit.SECONDS)).isTrue();
            work.protect(claim, () -> {
                var renewed = billing.renewReservationLease(job.getUser().getId(), job.getBillingReservationId(), job.getId());
                jobs.findByIdForUpdate(job.getId()).orElseThrow().setReservationExpiresAt(renewed.expiresAt());
                return null;
            });
            resume.countDown();
            assertThat(recovery.get(10, TimeUnit.SECONDS)).isZero();
            var running = jobs.findById(job.getId()).orElseThrow();
            assertThat(running.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
            assertThat(running.getBillingState()).isEqualTo(BillingState.RESERVED);
            assertThat(reservations.findById(job.getBillingReservationId()).orElseThrow().getState())
                    .isEqualTo(ReservationState.ACTIVE);
            assertBalance(job, 996, 4);
            assertThat(ledger.findByUserId(job.getUser().getId())).extracting(TokenTransaction::getType)
                    .containsExactly(TokenTransactionType.RESERVE);
            assertThat(work.protect(claim, () -> true)).isTrue();
            verifyNoInteractions(ai, assembly);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void recoveryScanAcrossSeveralOwnersUsesFourBoundedScalarQueries() {
        List<UUID> expiredIds = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            var job = fixture();
            work.claim(job.getId()).orElseThrow();
            transactions.executeWithoutResult(tx -> jobs.findByIdForUpdate(job.getId()).orElseThrow()
                    .setReservationExpiresAt(LocalDateTime.now(clock).minusMinutes(1)));
            expiredIds.add(job.getId());
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var batch = checkpoints.findRecoveryBatch(0, 2);
        assertThat(batch.expiredUncheckpointed()).hasSize(2).allMatch(expiredIds::contains);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    @Test void concurrentFailureNotificationsReleaseTheRealReservationOnce() throws Exception {
        var job = fixture();
        var claim = work.claim(job.getId()).orElseThrow();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> fail = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                work.failed(claim);
                return null;
            };
            Future<Void> first = executor.submit(fail), second = executor.submit(fail);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            facade.recoverStalledQuizGenerationFinalizations();
            assertReleasedOnce(job);
            verifyNoInteractions(ai, assembly);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void concurrentRecoveryOfPendingReleaseProducesOneLedgerEffect() throws Exception {
        var job = fixture();
        var claim = work.claim(job.getId()).orElseThrow();
        doThrow(new IllegalStateException("local billing transaction failure")).doNothing()
                .when(billingMetrics).incrementTokensReleased(eq(job.getUser().getId()), anyLong(), anyString());
        work.failed(claim);
        assertThat(jobs.findById(job.getId()).orElseThrow().getBillingState()).isEqualTo(BillingState.RESERVED);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> recover = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return facade.recoverStalledQuizGenerationFinalizations();
            };
            Future<Integer> first = executor.submit(recover), second = executor.submit(recover);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            facade.recoverStalledQuizGenerationFinalizations();
            assertReleasedOnce(job);
            verifyNoInteractions(ai, assembly);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void lostConfirmationAfterReleaseCommitIsReconciledWithoutAnotherLedgerEntry() {
        var job = fixture();
        var claim = work.claim(job.getId()).orElseThrow();
        doThrow(new IllegalStateException("local billing transaction failure")).doNothing()
                .when(billingMetrics).incrementTokensReleased(eq(job.getUser().getId()), anyLong(), anyString());
        work.failed(claim);
        String key = "quiz:" + job.getId() + ":finalization-release";
        doAnswer(call -> {
            call.callRealMethod(); // No caller transaction: the real billing transaction has committed.
            throw new IllegalStateException("simulated lost release confirmation");
        }).doCallRealMethod().when(billing).release(eq(job.getBillingReservationId()), anyString(), anyString(), eq(key));
        facade.recoverStalledQuizGenerationFinalizations();
        assertThat(jobs.findById(job.getId()).orElseThrow().getBillingState()).isEqualTo(BillingState.RESERVED);
        assertThat(reservations.findById(job.getBillingReservationId()).orElseThrow().getState())
                .isEqualTo(ReservationState.RELEASED);
        assertBalance(job, 1000, 0);
        facade.recoverStalledQuizGenerationFinalizations();
        assertReleasedOnce(job);
        verify(billing, times(2)).release(eq(job.getBillingReservationId()), anyString(), anyString(), eq(key));
        verifyNoInteractions(ai, assembly);
    }

    @Test void exhaustedRecoveryReleasesTheRealReservationWithoutAnAdditionalCharge() {
        var job = fixture();
        work.claim(job.getId()).orElseThrow();
        clock.advance(120);
        work.claim(job.getId()).orElseThrow();
        clock.advance(120);
        worker.execute(job.getId());
        worker.execute(job.getId());
        facade.recoverStalledQuizGenerationFinalizations();
        assertReleasedOnce(job);
        verifyNoInteractions(ai, assembly);
    }

    @Test void aFormerWorkerCannotReleaseOrChargeTheReplacementReservation() {
        var job = fixture();
        var former = work.claim(job.getId()).orElseThrow();
        clock.advance(120);
        var current = work.claim(job.getId()).orElseThrow();
        work.failed(former);
        work.finished(former);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertBalance(job, 996, 4);
        assertThat(ledger.findByUserId(job.getUser().getId())).extracting(TokenTransaction::getType)
                .containsExactly(TokenTransactionType.RESERVE);
        work.failed(current);
        assertReleasedOnce(job);
        verifyNoInteractions(ai, assembly);
    }

    @Test void aSweptExpiredReservationIsNeverRenewedOrChargedByRecovery() {
        var job = fixture();
        work.claim(job.getId()).orElseThrow();
        transactions.executeWithoutResult(tx -> {
            var expiredAt = LocalDateTime.now().minusMinutes(1);
            jobs.findByIdForUpdate(job.getId()).orElseThrow().setReservationExpiresAt(expiredAt);
            reservations.findById(job.getBillingReservationId()).orElseThrow().setExpiresAt(expiredAt);
        });
        billingService.expireReservations();
        clock.advance(120);
        worker.execute(job.getId());
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertReleasedOnce(job);
        verifyNoInteractions(ai, assembly);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartAndDuplicateFinalizationProduceOneQuizAndOneStoredTariffCharge(boolean billingEnabled) {
        flags.setBilling(billingEnabled);
        var job = fixture();
        work.claim(job.getId()).orElseThrow();
        clock.advance(120);
        doAnswer(call -> {
            UUID attempt = UUID.randomUUID();
            usage.recordStarted(job.getId(), attempt);
            usage.recordReported(job.getId(), attempt, 20_000L);
            var question = new Question();
            question.setType(QuestionType.TRUE_FALSE); question.setDifficulty(Difficulty.EASY);
            question.setQuestionText("A synthetic recovered question"); question.setContent("{\"answer\":true}");
            checkpoints.save(job.getId(), Map.of(0, List.of(question)));
            return null;
        }).when(ai).generateQuizFromDocumentAsync(eq(job.getId()), any());
        doAnswer(call -> {
            Category category = new Category(); category.setName("ledger-" + UUID.randomUUID());
            category.setDescription("Synthetic recovery category");
            categories.saveAndFlush(category); categoryIds.add(category.getId());
            return category;
        }).when(assembly).getOrCreateAICategory();
        when(assembly.resolveTags(any())).thenReturn(Set.of());
        doAnswer(call -> {
            Quiz quiz = new Quiz();
            quiz.setCreator(call.getArgument(0)); quiz.setCategory(call.getArgument(3));
            quiz.setTitle("Recovered synthetic quiz");
            quiz.setDescription("Local test fixture"); quiz.setVisibility(Visibility.PRIVATE);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setDifficulty(Difficulty.EASY);
            quiz.setEstimatedTime(1); quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz);
        }).when(assembly).createConsolidatedQuiz(any(), anyList(), any(), any(), anySet(), any(), anyInt());
        new GenerationWorkExecutorImpl(work, ai, new ObjectMapper().findAndRegisterModules()).execute(job.getId());
        clock.advance(1); // Advance to the next recovery scan without relying on wall-clock timing.
        facade.recoverStalledQuizGenerationFinalizations();
        facade.recoverStalledQuizGenerationFinalizations();
        worker.execute(job.getId());
        var completed = jobs.findById(job.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(completed.getBillingCommittedTokens()).isEqualTo(4L);
        assertThat(completed.getProviderLlmTokens()).isEqualTo(20_000L);
        assertThat(quizzes.existsById(completed.getGeneratedQuizId())).isTrue();
        assertThat(quizzes.findByCreatorId(job.getUser().getId())).hasSize(1);
        assertBalance(job, 996, 0);
        assertThat(ledger.findByUserId(job.getUser().getId())).extracting(TokenTransaction::getType)
                .containsExactlyInAnyOrder(TokenTransactionType.RESERVE, TokenTransactionType.COMMIT);
        verify(ai, times(1)).generateQuizFromDocumentAsync(eq(job.getId()), any());
        verify(assembly, times(1)).createConsolidatedQuiz(any(), anyList(), any(), any(), anySet(), any(), anyInt());
    }

    private QuizGenerationJob fixture() {
        return transactions.execute(tx -> {
            User owner = new User(); owner.setUsername("ledger-" + UUID.randomUUID());
            owner.setEmail(owner.getUsername() + "@example.test"); owner.setHashedPassword("synthetic");
            users.saveAndFlush(owner);
            Balance balance = new Balance(); balance.setUserId(owner.getId()); balance.setAvailableTokens(1000);
            balances.saveAndFlush(balance);
            var reserved = billing.reserve(owner.getId(), 4, "local-recovery-test", "reserve:" + UUID.randomUUID());
            Document source = new Document(); source.setUploadedBy(owner); source.setOriginalFilename("synthetic.txt");
            source.setContentType("text/plain"); source.setFilePath("/synthetic/never-read"); source.setFileSize(1000L);
            source.setStatus(Document.DocumentStatus.PROCESSED); documents.saveAndFlush(source);
            var job = new QuizGenerationJob(); job.setUser(owner); job.setDocumentId(source.getId());
            job.setStatus(GenerationStatus.PENDING); job.setStartedAt(LocalDateTime.now(clock));
            job.setTotalChunks(1); job.setTotalTasks(1);
            job.setRequestData("{\"documentId\":\"" + source.getId() + "\",\"questionsPerType\":{\"TRUE_FALSE\":1},\"difficulty\":\"EASY\",\"language\":\"en\"}");
            job.setBillingReservationId(reserved.id()); job.setBillingState(BillingState.RESERVED);
            job.setBillingEstimatedTokens(4L); job.setReservationExpiresAt(reserved.expiresAt());
            job.setBillingTariffVersion("v1-content-length-per-question-type"); job.setBillingBaseTokens(3L);
            job.setBillingTokensPerThousandCharacters(new BigDecimal("0.35"));
            job.setBillingQuotedContentCharacters(1000L); job.setBillingQuotedQuestionTypeCount(1);
            jobs.saveAndFlush(job); billing.attachReservationToJob(owner.getId(), reserved.id(), job.getId());
            work.enroll(job); fixtures.add(job); return job;
        });
    }

    private void assertReleasedOnce(QuizGenerationJob job) {
        assertThat(jobs.findById(job.getId()).orElseThrow().getBillingState()).isEqualTo(BillingState.RELEASED);
        assertThat(reservations.findById(job.getBillingReservationId()).orElseThrow().getState())
                .isEqualTo(ReservationState.RELEASED);
        assertBalance(job, 1000, 0);
        assertThat(ledger.findByUserId(job.getUser().getId())).extracting(TokenTransaction::getType)
                .containsExactlyInAnyOrder(TokenTransactionType.RESERVE, TokenTransactionType.RELEASE);
    }

    private void assertBalance(QuizGenerationJob job, long available, long reserved) {
        var balance = balances.findByUserId(job.getUser().getId()).orElseThrow();
        assertThat(balance.getAvailableTokens()).isEqualTo(available);
        assertThat(balance.getReservedTokens()).isEqualTo(reserved);
    }
}
