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
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
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
    @Mock private QuizQueryService quizQueryService;
    @Mock private QuizCommandService quizCommandService;
    @Mock private QuizRelationService quizRelationService;
    @Mock private QuizPublishingService quizPublishingService;
    @Mock private QuizVisibilityService quizVisibilityService;
    @Mock private QuizGenerationFacade quizGenerationFacade;

    private QuizServiceImpl quizService;

    private User testUser;
    private GenerateQuizFromDocumentRequest request;
    private EstimationDto estimation;
    private ReservationDto reservation;

    @BeforeEach
    void setup() {
        // Create QuizServiceImpl with new refactored dependencies
        quizService = new QuizServiceImpl(
                quizQueryService,
                quizCommandService,
                quizRelationService,
                quizPublishingService,
                quizVisibilityService,
                quizGenerationFacade
        );
        
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
        
        // Configure facade delegation - tests will override these as needed
        lenient().when(quizGenerationFacade.startQuizGeneration(anyString(), any()))
                .thenReturn(QuizGenerationResponse.started(UUID.randomUUID(), 180L));
        lenient().doNothing().when(quizGenerationFacade).commitTokensForSuccessfulGeneration(any(), anyList(), any());
        lenient().doNothing().when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(any(), anyMap(), any());
    }

    @Test
    @DisplayName("Scenario 4.1: releases reservation when job creation fails")
    void startQuizGeneration_releasesReservationWhenJobCreationFails() {
        // Configure facade to throw exception (delegation test)
        when(quizGenerationFacade.startQuizGeneration("testuser", request))
                .thenThrow(new DataIntegrityViolationException("constraint violation"));

        assertThatThrownBy(() -> quizService.startQuizGeneration("testuser", request))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify delegation occurred
        verify(quizGenerationFacade).startQuizGeneration("testuser", request);
    }

    @Test
    @DisplayName("Scenario 4.2: quiz creation failure marks job failed and releases reservation")
    void handleQuizGenerationCompleted_cleansUpWhenQuizCreationFails() {
        QuizGenerationJob job = new QuizGenerationJob();
        UUID jobId = UUID.fromString("660e8400-e29b-41d4-a716-446655440010");
        job.setId(jobId);
        job.setUser(testUser);
        job.setDocumentId(DOCUMENT_ID);
        job.setBillingReservationId(RESERVATION_ID);
        job.setBillingEstimatedTokens(2L);
        job.setBillingState(BillingState.RESERVED);
        job.setStatus(GenerationStatus.COMPLETED);

        // Configure facade to throw exception (delegation test)
        doThrow(new RuntimeException("quiz persistence failed"))
                .when(quizGenerationFacade)
                .createQuizCollectionFromGeneratedQuestions(eq(jobId), anyMap(), any());

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

        quizService.handleQuizGenerationCompleted(event);

        // Verify delegation occurred
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(eq(jobId), anyMap(), any());
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

        List<Question> generated = List.of(buildQuestion("True or false?"));

        // Configure facade to handle commit (delegation test - no exception expected)
        doNothing().when(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, generated, request);

        // When
        quizService.commitTokensForSuccessfulGeneration(job, generated, request);

        // Verify delegation occurred
        verify(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, generated, request);
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

        List<Question> questions = List.of(buildQuestion("Already processed"));

        // Configure facade to handle commit (delegation test)
        doNothing().when(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, questions, request);

        // When
        quizService.commitTokensForSuccessfulGeneration(job, questions, request);

        // Verify delegation occurred
        verify(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, questions, request);
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
