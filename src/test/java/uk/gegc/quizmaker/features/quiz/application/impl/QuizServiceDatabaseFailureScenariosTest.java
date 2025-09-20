package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
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
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuizServiceDatabaseFailureScenariosTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID RESERVATION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

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
    @Mock private EstimationService estimationService;
    @Mock private InternalBillingService internalBillingService;
    @Mock private FeatureFlags featureFlags;
    @Mock private AppPermissionEvaluator appPermissionEvaluator;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private QuizServiceImpl quizService;

    private User testUser;
    private GenerateQuizFromDocumentRequest request;
    private EstimationDto estimation;
    private ReservationDto reservation;

    @BeforeEach
    void setup() {
        testUser = new User();
        testUser.setId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");

        request = new GenerateQuizFromDocumentRequest(
                DOCUMENT_ID,
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Database Failure Test",
                "Testing database edge cases",
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        estimation = new EstimationDto(
                1_500L,
                2L,
                null,
                "usd",
                true,
                "~2 billing tokens",
                UUID.randomUUID()
        );

        reservation = new ReservationDto(
                RESERVATION_ID,
                testUser.getId(),
                ReservationState.ACTIVE,
                2L,
                0L,
                LocalDateTime.now().plusMinutes(30),
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        lenient().when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        lenient().when(estimationService.estimateQuizGeneration(eq(DOCUMENT_ID), any())).thenReturn(estimation);
        lenient().when(aiQuizGenerationService.calculateTotalChunks(any(), any())).thenReturn(4);
        lenient().when(aiQuizGenerationService.calculateEstimatedGenerationTime(anyInt(), any())).thenReturn(180);
        lenient().when(billingService.reserve(any(), anyLong(), anyString(), anyString())).thenReturn(reservation);
        lenient().when(featureFlags.isBilling()).thenReturn(true);
        lenient().when(appPermissionEvaluator.hasPermission(any(), any())).thenReturn(false);
        lenient().when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
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
    @DisplayName("Scenario 4.1: releases reservation when job creation fails")
    void startQuizGeneration_releasesReservationWhenJobCreationFails() {
        doThrow(new DataIntegrityViolationException("constraint violation"))
                .when(jobService)
                .createJob(any(), any(), anyString(), anyInt(), anyInt());

        assertThatThrownBy(() -> quizService.startQuizGeneration("testuser", request))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(billingService).release(
                eq(RESERVATION_ID),
                eq("job-creation-failed"),
                eq("quiz-generation"),
                isNull()
        );
        verify(aiQuizGenerationService, never()).generateQuizFromDocumentAsync(any(UUID.class), any());
    }

    @Test
    @DisplayName("Scenario 4.2: quiz creation failure marks job failed and releases reservation")
    void handleQuizGenerationCompleted_cleansUpWhenQuizCreationFails() {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(UUID.fromString("660e8400-e29b-41d4-a716-446655440010"));
        job.setUser(testUser);
        job.setDocumentId(DOCUMENT_ID);
        job.setBillingReservationId(RESERVATION_ID);
        job.setBillingEstimatedTokens(2L);
        job.setBillingState(BillingState.RESERVED);
        job.setStatus(GenerationStatus.COMPLETED);

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(internalBillingService.release(eq(RESERVATION_ID), contains("Quiz creation failed"), eq(job.getId().toString()), anyString()))
                .thenReturn(new ReleaseResultDto(RESERVATION_ID, 2L));

        QuizServiceImpl spyService = spy(quizService);
        doThrow(new RuntimeException("quiz persistence failed"))
                .when(spyService)
                .createQuizCollectionFromGeneratedQuestions(eq(job.getId()), anyMap(), any());

        Question question = new Question();
        question.setQuestionText("Sample question");
        question.setType(QuestionType.MCQ_SINGLE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setContent("{}\n");

        QuizGenerationCompletedEvent event = new QuizGenerationCompletedEvent(
                this,
                job.getId(),
                Map.of(1, List.of(question)),
                request,
                List.of(question)
        );

        spyService.handleQuizGenerationCompleted(event);

        ArgumentCaptor<QuizGenerationJob> jobCaptor = ArgumentCaptor.forClass(QuizGenerationJob.class);
        verify(jobRepository, atLeastOnce()).save(jobCaptor.capture());
        QuizGenerationJob persisted = jobCaptor.getValue();

        assertThat(persisted.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(persisted.getBillingState()).isEqualTo(BillingState.RELEASED);
        verify(internalBillingService).release(eq(RESERVATION_ID), contains("Quiz creation failed"), eq(job.getId().toString()), anyString());
    }

    @Test
    @DisplayName("Scenario 4.3: billing failure during commit stores error without state transition")
    void commitTokensForSuccessfulGeneration_capturesBillingFailures() {
        QuizGenerationJob job = new QuizGenerationJob();
        UUID jobId = UUID.fromString("770e8400-e29b-41d4-a716-446655440020");
        job.setId(jobId);
        job.setBillingReservationId(RESERVATION_ID);
        job.setBillingEstimatedTokens(5_000L);
        job.setInputPromptTokens(1_500L);
        job.setBillingState(BillingState.RESERVED);
        job.setStatus(GenerationStatus.COMPLETED);

        QuizGenerationJob lockedJob = new QuizGenerationJob();
        lockedJob.setId(jobId);
        lockedJob.setBillingReservationId(RESERVATION_ID);
        lockedJob.setBillingEstimatedTokens(5_000L);
        lockedJob.setInputPromptTokens(1_500L);
        lockedJob.setBillingState(BillingState.RESERVED);
        lockedJob.setStatus(GenerationStatus.COMPLETED);

        when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(lockedJob));
        when(estimationService.computeActualBillingTokens(anyList(), eq(Difficulty.MEDIUM), eq(1_500L))).thenReturn(3_000L);
        doThrow(new RuntimeException("billing gateway unavailable"))
                .when(internalBillingService)
                .commit(eq(RESERVATION_ID), eq(3_000L), eq("quiz-generation"), anyString());

        List<Question> generated = List.of(buildQuestion("True or false?"));

        quizService.commitTokensForSuccessfulGeneration(job, generated, request);

        assertThat(job.getBillingState()).isEqualTo(BillingState.RESERVED);
        verify(internalBillingService).commit(eq(RESERVATION_ID), eq(3_000L), eq("quiz-generation"), anyString());
        verify(internalBillingService, never()).release(eq(RESERVATION_ID), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Scenario 4.4: repeated commit attempts remain idempotent after optimistic handling")
    void commitTokensForSuccessfulGeneration_returnsImmediatelyWhenAlreadyCommitted() {
        QuizGenerationJob job = new QuizGenerationJob();
        UUID jobId = UUID.fromString("880e8400-e29b-41d4-a716-446655440030");
        job.setId(jobId);
        job.setBillingReservationId(RESERVATION_ID);
        job.setBillingEstimatedTokens(2_000L);
        job.setInputPromptTokens(800L);
        job.setBillingState(BillingState.COMMITTED);
        job.setStatus(GenerationStatus.COMPLETED);
        job.addBillingIdempotencyKey("commit", "quiz:" + jobId + ":commit");

        QuizGenerationJob lockedJob = new QuizGenerationJob();
        lockedJob.setId(jobId);
        lockedJob.setBillingReservationId(RESERVATION_ID);
        lockedJob.setBillingEstimatedTokens(2_000L);
        lockedJob.setInputPromptTokens(800L);
        lockedJob.setBillingState(BillingState.COMMITTED);
        lockedJob.setStatus(GenerationStatus.COMPLETED);
        lockedJob.addBillingIdempotencyKey("commit", "quiz:" + jobId + ":commit");

        when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(lockedJob));

        quizService.commitTokensForSuccessfulGeneration(job, List.of(buildQuestion("Already processed")), request);

        verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
    }

    private Question buildQuestion(String text) {
        Question question = new Question();
        question.setQuestionText(text);
        question.setType(QuestionType.TRUE_FALSE);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setContent("{\"statement\":\"" + text + "\",\"answer\":true}");
        return question;
    }
}
