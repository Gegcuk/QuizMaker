package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuizServiceImplBillingDelegationTest {

    @Mock private QuizRepository quizRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private TagRepository tagRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private QuizMapper quizMapper;
    @Mock private UserRepository userRepository;
    @Mock private QuestionHandlerFactory questionHandlerFactory;
    @Mock private QuizGenerationJobRepository jobRepository;
    @Mock private QuizGenerationJobService jobService;
    @Mock private AiQuizGenerationService aiQuizGenerationService;
    @Mock private DocumentProcessingService documentProcessingService;
    @Mock private QuizHashCalculator quizHashCalculator;
    @Mock private BillingService billingService;
    @Mock private InternalBillingService internalBillingService;
    @Mock private EstimationService estimationService;
    @Mock private FeatureFlags featureFlags;
    @Mock private AppPermissionEvaluator appPermissionEvaluator;
    @Mock private TransactionTemplate transactionTemplate;

    private ApplicationEventPublisher applicationEventPublisher;


    private QuizServiceImpl quizService;

    @BeforeEach
    void setUp() {
        quizService = new QuizServiceImpl(
                quizRepository,
                questionRepository,
                tagRepository,
                categoryRepository,
                quizMapper,
                userRepository,
                questionHandlerFactory,
                jobRepository,
                jobService,
                aiQuizGenerationService,
                documentProcessingService,
                quizHashCalculator,
                billingService,
                internalBillingService,
                estimationService,
                featureFlags,
                appPermissionEvaluator,
                applicationEventPublisher,
                transactionTemplate
        );
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            TransactionCallbackWithoutResult callback = invocation.getArgument(0);
            callback.doInTransaction(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("Scenario 2.1: exact usage commits all reserved tokens without releasing remainder")
    void commitTokensForSuccessfulGenerationExactUsageCommitsAllTokens() {
        UUID jobId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setBillingEstimatedTokens(100L);
        job.setStatus(GenerationStatus.COMPLETED);
        job.setInputPromptTokens(30L);
        job.setReservationExpiresAt(LocalDateTime.now().plusMinutes(10));

        when(jobRepository.findByIdForUpdate(eq(jobId))).thenReturn(Optional.of(job));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(estimationService.computeActualBillingTokens(anyList(), eq(Difficulty.MEDIUM), eq(30L))).thenReturn(100L);

        CommitResultDto commitResult = new CommitResultDto(reservationId, 100L, 0L);
        when(internalBillingService.commit(eq(reservationId), eq(100L), eq("quiz-generation"), anyString())).thenReturn(commitResult);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Quiz title",
                "Quiz description",
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("Sample question");
        question.setContent("{}");

        quizService.commitTokensForSuccessfulGeneration(job, List.of(question), request);

        ArgumentCaptor<String> commitKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalBillingService).commit(eq(reservationId), eq(100L), eq("quiz-generation"), commitKeyCaptor.capture());
        assertThat(commitKeyCaptor.getValue()).isEqualTo("quiz:" + jobId + ":commit");
        verify(internalBillingService, Mockito.never()).release(any(), any(), any(), any());
        verify(jobRepository).save(job);

        assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(job.getBillingCommittedTokens()).isEqualTo(100L);
        assertThat(job.getActualTokens()).isEqualTo(100L);
        assertThat(job.getWasCappedAtReserved()).isFalse();
        assertThat(job.getBillingIdempotencyKeys()).contains("\"commit\"");
        assertThat(job.getLastBillingError()).isNull();
    }

    @Test
    @DisplayName("Scenario 2.2: under-usage releases remainder after commit")
    void commitTokensForSuccessfulGenerationUnderUsageReleasesRemainder() {
        UUID jobId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setBillingEstimatedTokens(100L);
        job.setStatus(GenerationStatus.COMPLETED);
        job.setInputPromptTokens(20L);
        job.setReservationExpiresAt(LocalDateTime.now().plusMinutes(5));

        when(jobRepository.findByIdForUpdate(eq(jobId))).thenReturn(Optional.of(job));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(estimationService.computeActualBillingTokens(anyList(), eq(Difficulty.MEDIUM), eq(20L))).thenReturn(60L);
        CommitResultDto commitResult = new CommitResultDto(reservationId, 60L, 0L);
        when(internalBillingService.commit(eq(reservationId), eq(60L), eq("quiz-generation"), anyString())).thenReturn(commitResult);
        when(internalBillingService.release(eq(reservationId), eq("commit-remainder"), eq("quiz-generation"), isNull()))
                .thenReturn(new ReleaseResultDto(reservationId, 40L));

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Quiz title",
                "Quiz description",
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("Sample question");
        question.setContent("{}");

        quizService.commitTokensForSuccessfulGeneration(job, List.of(question), request);

        ArgumentCaptor<String> commitKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalBillingService).commit(eq(reservationId), eq(60L), eq("quiz-generation"), commitKeyCaptor.capture());
        assertThat(commitKeyCaptor.getValue()).isEqualTo("quiz:" + jobId + ":commit");
        verify(internalBillingService).release(eq(reservationId), eq("commit-remainder"), eq("quiz-generation"), isNull());
        verify(jobRepository).save(job);
        verifyNoInteractions(billingService);

        assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(job.getBillingCommittedTokens()).isEqualTo(60L);
        assertThat(job.getActualTokens()).isEqualTo(60L);
        assertThat(job.getBillingIdempotencyKeys()).contains("\"commit\":");
    }

    @Test
    @DisplayName("Scenario 2.3: over-usage caps committed tokens at reserved amount")
    void commitTokensForSuccessfulGenerationCappedAtReservedWhenActualExceeds() {
        UUID jobId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setBillingEstimatedTokens(100L);
        job.setStatus(GenerationStatus.COMPLETED);
        job.setInputPromptTokens(25L);
        job.setReservationExpiresAt(LocalDateTime.now().plusMinutes(10));

        when(jobRepository.findByIdForUpdate(eq(jobId))).thenReturn(Optional.of(job));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(estimationService.computeActualBillingTokens(anyList(), eq(Difficulty.MEDIUM), eq(25L))).thenReturn(150L);

        CommitResultDto commitResult = new CommitResultDto(reservationId, 100L, 0L);
        when(internalBillingService.commit(eq(reservationId), eq(100L), eq("quiz-generation"), anyString())).thenReturn(commitResult);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Quiz title",
                "Quiz description",
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("Sample question");
        question.setContent("{}");

        quizService.commitTokensForSuccessfulGeneration(job, List.of(question), request);

        ArgumentCaptor<String> commitKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(internalBillingService).commit(eq(reservationId), eq(100L), eq("quiz-generation"), commitKeyCaptor.capture());
        assertThat(commitKeyCaptor.getValue()).isEqualTo("quiz:" + jobId + ":commit");
        verify(internalBillingService, Mockito.never()).release(any(), any(), any(), any());
        verify(jobRepository).save(job);

        assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
        assertThat(job.getBillingCommittedTokens()).isEqualTo(100L);
        assertThat(job.getActualTokens()).isEqualTo(150L);
        assertThat(job.getWasCappedAtReserved()).isTrue();
        assertThat(job.getBillingIdempotencyKeys()).contains("\"commit\"");
    }

    @Test
    @DisplayName("Scenario 2.4: skip commit when reservation already expired")
    void commitTokensForSuccessfulGenerationSkipsWhenReservationExpired() {
        UUID jobId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setBillingEstimatedTokens(50L);
        job.setStatus(GenerationStatus.COMPLETED);
        job.setReservationExpiresAt(LocalDateTime.now().minusMinutes(2));

        when(jobRepository.findByIdForUpdate(eq(jobId))).thenReturn(Optional.of(job));

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Expired Reservation Quiz",
                "Expired Reservation Description",
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        Question question = new Question();
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText("Sample question");
        question.setContent("{}");

        quizService.commitTokensForSuccessfulGeneration(job, List.of(question), request);

        verify(internalBillingService, never()).commit(any(), anyLong(), any(), any());
        verify(internalBillingService, never()).release(any(), any(), any(), any());
        verify(jobRepository, never()).save(any());
    }

    @Test
    void handleQuizGenerationCompletedFailureReleasesReservationViaInternalService() {
        QuizServiceImpl spyService = Mockito.spy(quizService);

        doThrow(new RuntimeException("boom"))
                .when(spyService).createQuizCollectionFromGeneratedQuestions(any(), any(), any());

        UUID jobId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setStatus(GenerationStatus.PROCESSING);

        when(jobRepository.findById(eq(jobId))).thenReturn(Optional.of(job));
        when(jobRepository.save(any(QuizGenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                null,
                null,
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        QuizGenerationCompletedEvent event = new QuizGenerationCompletedEvent(
                this,
                jobId,
                Map.of(),
                request,
                List.of()
        );

        spyService.handleQuizGenerationCompleted(event);

        verify(internalBillingService).release(
                eq(reservationId),
                eq("Quiz creation failed: boom"),
                eq(jobId.toString()),
                eq("quiz:" + jobId + ":release")
        );
        verify(jobRepository).save(job);
        assertThat(job.getBillingState()).isEqualTo(BillingState.RELEASED);
    }
}


