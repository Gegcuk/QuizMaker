package uk.gegc.quizmaker.features.quiz.application.generation.impl;

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
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

import java.time.LocalDateTime;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        "spring.jpa.hibernate.ddl-auto=update"
})
@Import({QuizGenerationFacadeImpl.class, QuizGenerationEntitlementMySqlIntegrationTest.TestConfiguration.class})
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
            return new QuizJobProperties();
        }

        @Bean
        QuizAssemblyService quizAssemblyService(QuizRepository quizRepository, CategoryRepository categoryRepository) {
            return new PersistingQuizAssemblyService(quizRepository, categoryRepository);
        }
    }

    private static final class PersistingQuizAssemblyService implements QuizAssemblyService {
        private final QuizRepository quizRepository;
        private final CategoryRepository categoryRepository;

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
            return saveQuiz(user, category, "Chunk " + chunkIndex, request);
        }

        @Override
        public Quiz createConsolidatedQuiz(User user, List<Question> questions,
                                           GenerateQuizFromDocumentRequest request, Category category,
                                           Set<uk.gegc.quizmaker.features.tag.domain.model.Tag> tags,
                                           UUID documentId, int chunkCount) {
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
    private QuizRepository quizRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private CategoryRepository categoryRepository;

    private UUID userId;
    private UUID jobId;

    @AfterEach
    void cleanUp() {
        quizRepository.deleteAllInBatch();
        jobRepository.deleteAllInBatch();
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
        job.setRequestData("{}");
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

    private GenerateQuizFromDocumentRequest request(UUID documentId) {
        return new GenerateQuizFromDocumentRequest(
                documentId, QuizScope.ENTIRE_DOCUMENT, null, null, null,
                "Entitlement integration quiz", null,
                Map.of(QuestionType.MCQ_SINGLE, 1), Difficulty.MEDIUM, 1, null, List.of());
    }

    private Question question() {
        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setQuestionText("A generated question");
        return question;
    }
}
