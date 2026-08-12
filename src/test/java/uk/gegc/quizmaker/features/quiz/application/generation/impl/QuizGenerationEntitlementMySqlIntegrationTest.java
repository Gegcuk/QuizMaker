package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.GenerationTariffService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFinalizationClaim;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationIdempotencyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationRequestCanonicalizer;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationOutputCheckpointRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the entitlement invariant against MySQL with a fake billing port.
 * The fake represents a deterministic local failure; it never contacts a
 * payment provider.
 */
@Tag("db-serial")
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "quiz.jobs.finalization.recovery-grace-seconds=0",
        "quiz.jobs.finalization.recovery-batch-size=50"
})
@Import({
        QuizGenerationFacadeImpl.class,
        QuizGenerationCheckpointCodec.class,
        QuizGenerationCheckpointServiceImpl.class,
        QuizGenerationEntitlementMySqlIntegrationTest.TestConfiguration.class
})
@DisplayName("Quiz generation entitlement MySQL integration")
class QuizGenerationEntitlementMySqlIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        QuizJobProperties quizJobProperties() {
            QuizJobProperties properties = new QuizJobProperties();
            properties.getFinalization().setRecoveryGraceSeconds(0);
            properties.getFinalization().setRecoveryBatchSize(50);
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        Clock systemClock() {
            return Clock.systemDefaultZone();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        PersistingQuizAssemblyService quizAssemblyService(
                QuizRepository quizRepository,
                CategoryRepository categoryRepository
        ) {
            return new PersistingQuizAssemblyService(quizRepository, categoryRepository);
        }
    }

    private static final class PersistingQuizAssemblyService implements QuizAssemblyService {
        private final QuizRepository quizRepository;
        private final CategoryRepository categoryRepository;
        private final List<String> assembledQuestionTexts = new ArrayList<>();

        private PersistingQuizAssemblyService(QuizRepository quizRepository, CategoryRepository categoryRepository) {
            this.quizRepository = quizRepository;
            this.categoryRepository = categoryRepository;
        }

        @Override
        public Category getOrCreateAICategory() {
            return categoryRepository.findByName("Entitlement integration category")
                    .orElseGet(() -> {
                        Category category = new Category();
                        category.setName("Entitlement integration category");
                        category.setDescription("Used only by the entitlement integration test");
                        return categoryRepository.save(category);
                    });
        }

        @Override
        public Set<uk.gegc.quizmaker.features.tag.domain.model.Tag> resolveTags(GenerateQuizFromDocumentRequest request) {
            return Set.of();
        }

        @Override
        public Quiz createChunkQuiz(User user, List<Question> questions, int chunkIndex,
                                    GenerateQuizFromDocumentRequest request, Category category,
                                    Set<uk.gegc.quizmaker.features.tag.domain.model.Tag> tags,
                                    UUID documentId) {
            recordQuestions(questions);
            return saveQuiz(user, category, "Chunk " + chunkIndex, request);
        }

        @Override
        public Quiz createConsolidatedQuiz(User user, List<Question> questions,
                                           GenerateQuizFromDocumentRequest request, Category category,
                                           Set<uk.gegc.quizmaker.features.tag.domain.model.Tag> tags,
                                           UUID documentId, int chunkCount) {
            recordQuestions(questions);
            return saveQuiz(user, category, "Entitlement integration quiz", request);
        }

        @Override
        public String generateChunkTitle(int chunkIndex, List<Question> questions) {
            return "Chunk " + chunkIndex;
        }

        @Override
        public String ensureUniqueTitle(User user, String requestedTitle) {
            return requestedTitle;
        }

        private synchronized void recordQuestions(List<Question> questions) {
            assembledQuestionTexts.addAll(
                    questions.stream().map(Question::getQuestionText).toList()
            );
        }

        private synchronized List<String> assembledQuestionTexts() {
            return List.copyOf(assembledQuestionTexts);
        }

        private synchronized void clearRecordedQuestions() {
            assembledQuestionTexts.clear();
        }

        private Quiz saveQuiz(User user, Category category, String title, GenerateQuizFromDocumentRequest request) {
            Quiz quiz = new Quiz();
            quiz.setCreator(user);
            quiz.setCategory(category);
            quiz.setTitle(title + " " + UUID.randomUUID());
            quiz.setDescription("Created inside the entitlement finalization transaction");
            quiz.setVisibility(Visibility.PRIVATE);
            quiz.setStatus(QuizStatus.DRAFT);
            quiz.setDifficulty(request.difficulty());
            quiz.setEstimatedTime(1);
            quiz.setIsTimerEnabled(false);
            quiz.setIsRepetitionEnabled(false);
            return quizRepository.saveAndFlush(quiz);
        }
    }

    @MockitoBean private QuizGenerationJobService jobService;
    @MockitoBean private AiQuizGenerationService aiQuizGenerationService;
    @MockitoBean private DocumentProcessingService documentProcessingService;
    @MockitoBean private BillingService billingService;
    @MockitoBean private InternalBillingService internalBillingService;
    @MockitoBean private EstimationService estimationService;
    @MockitoBean private GenerationTariffService generationTariffService;
    @MockitoBean private FeatureFlags featureFlags;
    @MockitoBean private ApplicationEventPublisher applicationEventPublisher;
    @MockitoBean private QuizGenerationIdempotencyService idempotencyService;
    @MockitoBean private QuizGenerationRequestCanonicalizer requestCanonicalizer;

    @org.springframework.beans.factory.annotation.Autowired
    private QuizGenerationFacadeImpl facade;

    @org.springframework.beans.factory.annotation.Autowired
    private UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private QuizGenerationJobRepository jobRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private QuizGenerationOutputCheckpointRepository checkpointRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private QuizGenerationCheckpointService checkpointService;

    @org.springframework.beans.factory.annotation.Autowired
    private QuizJobProperties quizJobProperties;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private EntityManagerFactory entityManagerFactory;

    @org.springframework.beans.factory.annotation.Autowired
    private QuizRepository quizRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private PersistingQuizAssemblyService quizAssemblyService;

    @org.springframework.beans.factory.annotation.Autowired
    private CategoryRepository categoryRepository;

    private UUID userId;
    private UUID jobId;

    @AfterEach
    void cleanUp() {
        quizAssemblyService.clearRecordedQuestions();
        if (jobId != null) {
            checkpointService.delete(jobId);
        }
        if (userId != null) {
            quizRepository.deleteAllInBatch(quizRepository.findByCreatorId(userId));
        }
        if (jobId != null && jobRepository.existsById(jobId)) {
            jobRepository.deleteById(jobId);
        }
        categoryRepository.findByName("Entitlement integration category").ifPresent(categoryRepository::delete);
        if (userId != null) {
            userRepository.findById(userId).ifPresent(userRepository::delete);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Settlement failure rolls back the quiz and leaves the job non-completed")
    void settlementFailureRollsBackContentAndCompletion() {
        QuizGenerationJob job = persistFinalizingJob();
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("deterministic billing failure"));

        assertThatThrownBy(() -> facade.createQuizCollectionFromGeneratedQuestions(
                job.getId(), Map.of(0, List.of(question())), request(job.getDocumentId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deterministic billing failure");

        assertThat(quizRepository.findByCreatorId(userId)).isEmpty();
        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(reloaded.getGeneratedQuizId()).isNull();
        assertThat(reloaded.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.FINALIZING);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Successful settlement publishes one quiz and a durably completed job together")
    void successfulSettlementPublishesContentAndCompletionTogether() {
        QuizGenerationJob job = persistFinalizingJob();
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenReturn(new CommitResultDto(job.getBillingReservationId(), 4L, 0L));

        facade.createQuizCollectionFromGeneratedQuestions(
                job.getId(), Map.of(0, List.of(question())), request(job.getDocumentId()));

        assertThat(quizRepository.findByCreatorId(userId)).hasSize(1);
        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(reloaded.getGeneratedQuizId()).isNotNull();
        assertThat(reloaded.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(reloaded.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.SUCCEEDED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Concurrent finalizers create one quiz and one settlement, then a retry is already finalized")
    void concurrentFinalizersCreateOneQuizAndOneSettlement() throws Exception {
        QuizGenerationJob job = persistFinalizingCandidate();
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenReturn(new CommitResultDto(job.getBillingReservationId(), 4L, 0L));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<QuizGenerationFinalizationClaim> first = executor.submit(() -> finalizeWhenClaimed(
                    job.getId(), job.getDocumentId(), ready, start));
            Future<QuizGenerationFinalizationClaim> second = executor.submit(() -> finalizeWhenClaimed(
                    job.getId(), job.getDocumentId(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<QuizGenerationFinalizationClaim> claims = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(claims).containsExactlyInAnyOrder(
                    QuizGenerationFinalizationClaim.CLAIMED,
                    QuizGenerationFinalizationClaim.IN_PROGRESS);
            assertThat(quizRepository.findByCreatorId(userId)).hasSize(1);
            verify(internalBillingService, times(1)).commit(any(), anyLong(), any(), any());
            assertThat(facade.claimQuizGenerationFinalization(job.getId()))
                    .isEqualTo(QuizGenerationFinalizationClaim.ALREADY_FINALIZED);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Restart recovery publishes a durable checkpoint without another provider call")
    void restartRecoveryFinalizesDurableCheckpoint() {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointService.save(job.getId(), Map.of(0, List.of(question())));
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenReturn(new CommitResultDto(job.getBillingReservationId(), 4L, 0L));

        int recovered = facade.recoverStalledQuizGenerationFinalizations();

        assertThat(recovered).isEqualTo(1);
        assertThat(quizRepository.findByCreatorId(userId)).hasSize(1);
        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(reloaded.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.SUCCEEDED);
        assertThat(reloaded.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(checkpointRepository.existsById(job.getId())).isFalse();
        assertThat(quizAssemblyService.assembledQuestionTexts()).containsExactly("A generated question");
        verify(internalBillingService, times(1)).commit(any(), anyLong(), any(), any());
        verifyNoInteractions(aiQuizGenerationService);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Concurrent restart scans consume one checkpoint and settle once")
    void concurrentRestartScansFinalizeCheckpointOnce() throws Exception {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointService.save(job.getId(), Map.of(0, List.of(question())));
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenReturn(new CommitResultDto(job.getBillingReservationId(), 4L, 0L));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> recoverWhenReleased(ready, start));
            Future<Integer> second = executor.submit(() -> recoverWhenReleased(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(quizRepository.findByCreatorId(userId)).hasSize(1);
            verify(internalBillingService, times(1)).commit(any(), anyLong(), any(), any());
            assertThat(checkpointRepository.existsById(job.getId())).isFalse();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Concurrent restart scans reclaim one stale checkpointed finalization")
    void concurrentRestartScansReclaimStaleCheckpointOnce() throws Exception {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointService.save(job.getId(), Map.of(0, List.of(question())));
        job.beginFinalization(LocalDateTime.now().minusMinutes(10));
        jobRepository.saveAndFlush(job);
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenReturn(new CommitResultDto(job.getBillingReservationId(), 4L, 0L));

        int originalRecoveryGraceSeconds = quizJobProperties.getFinalization().getRecoveryGraceSeconds();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Keep the first scanner's renewed claim leased while the second scanner waits on the row lock.
            quizJobProperties.getFinalization().setRecoveryGraceSeconds(60);
            Future<Integer> first = executor.submit(() -> recoverWhenReleased(ready, start));
            Future<Integer> second = executor.submit(() -> recoverWhenReleased(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(quizRepository.findByCreatorId(userId)).hasSize(1);
            verify(internalBillingService, times(1)).commit(any(), anyLong(), any(), any());
            assertThat(checkpointRepository.existsById(job.getId())).isFalse();
        } finally {
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                quizJobProperties.getFinalization().setRecoveryGraceSeconds(originalRecoveryGraceSeconds);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Failed checkpoint finalization rolls back content and retains output for compensation")
    void failedCheckpointFinalizationRetainsOutputAfterRollback() {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointService.save(job.getId(), Map.of(0, List.of(question())));
        assertThat(facade.claimQuizGenerationFinalization(job.getId()))
                .isEqualTo(QuizGenerationFinalizationClaim.CLAIMED);
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("deterministic settlement failure"));

        assertThatThrownBy(() -> facade.createQuizCollectionFromCheckpoint(job.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deterministic settlement failure");

        assertThat(quizRepository.findByCreatorId(userId)).isEmpty();
        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(reloaded.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.FINALIZING);
        assertThat(checkpointRepository.existsById(job.getId())).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Malformed durable output fails visibly, releases billing, and creates no quiz")
    void malformedCheckpointFailsAndReleasesWithoutContent() {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointRepository.saveAndFlush(new uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOutputCheckpoint(
                job.getId(), 1, "not-json", 1, LocalDateTime.now().minusMinutes(1)));

        int recovered = facade.recoverStalledQuizGenerationFinalizations();

        assertThat(recovered).isEqualTo(1);
        assertThat(quizRepository.findByCreatorId(userId)).isEmpty();
        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(reloaded.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.FAILED);
        assertThat(reloaded.getBillingState()).isEqualTo(BillingState.RELEASED);
        assertThat(checkpointRepository.existsById(job.getId())).isFalse();
        verify(internalBillingService, never()).commit(any(), anyLong(), any(), any());
        verify(internalBillingService).release(
                eq(job.getBillingReservationId()),
                eq("generation-finalization-failed"),
                eq("quiz-generation"),
                eq("quiz:" + job.getId() + ":finalization-release")
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Expired paid processing job without output fails and releases without provider retry")
    void expiredUncheckpointedJobFailsAndReleases() {
        QuizGenerationJob job = persistFinalizingCandidate();
        job.setReservationExpiresAt(LocalDateTime.now().minusMinutes(1));
        jobRepository.saveAndFlush(job);

        int recovered = facade.recoverStalledQuizGenerationFinalizations();

        assertThat(recovered).isEqualTo(1);
        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(reloaded.getBillingState()).isEqualTo(BillingState.RELEASED);
        assertThat(quizRepository.findByCreatorId(userId)).isEmpty();
        verifyNoInteractions(aiQuizGenerationService);
        verify(internalBillingService, never()).commit(any(), anyLong(), any(), any());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Cancellation deletes durable output and prevents later recovery publication")
    void cancellationWinsBeforeCheckpointRecovery() {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointService.save(job.getId(), Map.of(0, List.of(question())));

        facade.cancelGenerationJob(job.getId(), job.getUser().getUsername());
        int recovered = facade.recoverStalledQuizGenerationFinalizations();

        assertThat(recovered).isZero();
        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(reloaded.getFinalizationState()).isEqualTo(QuizGenerationFinalizationState.CANCELLED);
        assertThat(checkpointRepository.existsById(job.getId())).isFalse();
        assertThat(quizRepository.findByCreatorId(userId)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Concurrent cancellation and checkpoint recovery produce one terminal outcome")
    void concurrentCancellationAndRecoveryProduceOneTerminalOutcome() throws Exception {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointService.save(job.getId(), Map.of(0, List.of(question())));
        when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(4L);
        when(internalBillingService.commit(any(), anyLong(), any(), any()))
                .thenReturn(new CommitResultDto(job.getBillingReservationId(), 4L, 0L));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancellation = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent cancellation did not start in time");
                }
                try {
                    facade.cancelGenerationJob(job.getId(), job.getUser().getUsername());
                } catch (uk.gegc.quizmaker.shared.exception.ValidationException alreadyTerminal) {
                    // Completion acquired the same job lock first; that is the other valid outcome.
                }
                return null;
            });
            Future<Integer> recovery = executor.submit(() -> recoverWhenReleased(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            cancellation.get(10, TimeUnit.SECONDS);
            recovery.get(10, TimeUnit.SECONDS);

            QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isIn(GenerationStatus.CANCELLED, GenerationStatus.COMPLETED);
            assertThat(checkpointRepository.existsById(job.getId())).isFalse();
            if (reloaded.getStatus() == GenerationStatus.CANCELLED) {
                assertThat(quizRepository.findByCreatorId(userId)).isEmpty();
                verify(internalBillingService, never()).commit(any(), anyLong(), any(), any());
                verify(billingService, times(1)).release(any(), anyString(), anyString(), anyString());
            } else {
                assertThat(quizRepository.findByCreatorId(userId)).hasSize(1);
                verify(internalBillingService, times(1)).commit(any(), anyLong(), any(), any());
                verify(billingService, never()).release(any(), anyString(), anyString(), anyString());
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Recovery candidate scan uses four fixed queries without loading one relation per job")
    void recoveryCandidateScanHasNoNPlusOneQueries() {
        QuizGenerationJob job = persistFinalizingCandidate();
        checkpointService.save(job.getId(), Map.of(0, List.of(question())));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        QuizGenerationCheckpointService.RecoveryBatch batch = checkpointService.findRecoveryBatch(0, 50);

        assertThat(batch.checkpointedNotStarted()).contains(job.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4L);
    }

    private QuizGenerationJob persistFinalizingJob() {
        QuizGenerationJob job = persistFinalizingCandidate();
        job.beginFinalization(LocalDateTime.now());
        return jobRepository.saveAndFlush(job);
    }

    private QuizGenerationJob persistFinalizingCandidate() {
        User user = new User();
        user.setUsername("entitlement-" + UUID.randomUUID());
        user.setEmail(user.getUsername() + "@example.com");
        user.setHashedPassword("not-used-by-this-test");
        user.setActive(true);
        user = userRepository.saveAndFlush(user);
        userId = user.getId();

        QuizGenerationJob job = new QuizGenerationJob();
        job.setUser(user);
        job.setDocumentId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        try {
            job.setRequestData(objectMapper.writeValueAsString(request(job.getDocumentId())));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create test generation request", exception);
        }
        job.setBillingReservationId(UUID.randomUUID());
        job.setBillingState(BillingState.RESERVED);
        job.setBillingEstimatedTokens(10L);
        job.setInputPromptTokens(10L);
        job.setReservationExpiresAt(LocalDateTime.now().plusMinutes(30));
        job = jobRepository.saveAndFlush(job);
        jobId = job.getId();
        return job;
    }

    private QuizGenerationFinalizationClaim finalizeWhenClaimed(
            UUID generationJobId,
            UUID documentId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent finalizer test did not start in time");
        }
        QuizGenerationFinalizationClaim claim = facade.claimQuizGenerationFinalization(generationJobId);
        if (claim.shouldFinalize()) {
            facade.createQuizCollectionFromGeneratedQuestions(
                    generationJobId, Map.of(0, List.of(question())), request(documentId));
        }
        return claim;
    }

    private int recoverWhenReleased(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent recovery test did not start in time");
        }
        return facade.recoverStalledQuizGenerationFinalizations();
    }

    private GenerateQuizFromDocumentRequest request(UUID documentId) {
        return new GenerateQuizFromDocumentRequest(
                documentId, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                "Entitlement integration quiz", null,
                Map.of(QuestionType.MCQ_SINGLE, 1), Difficulty.MEDIUM, 1, null, List.of());
    }

    private Question question() {
        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("A generated question");
        question.setContent("{\"correctOptionId\":\"answer-1\"}");
        return question;
    }
}
