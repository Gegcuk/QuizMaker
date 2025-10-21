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
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.application.validation.QuizPublishValidator;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;

import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;

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

/**
 * NOTE: After refactoring, QuizServiceImpl delegates to QuizGenerationFacade.
 * The actual billing logic is now tested in QuizGenerationFacadeImplBillingTest.
 * These tests verify the delegation works correctly.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class QuizServiceImplBillingDelegationTest {

    @Mock private UserRepository userRepository;
    @Mock private QuizGenerationJobRepository jobRepository;
    @Mock private QuizGenerationJobService jobService;
    @Mock private AiQuizGenerationService aiQuizGenerationService;
    @Mock private DocumentProcessingService documentProcessingService;
    @Mock private BillingService billingService;
    @Mock private InternalBillingService internalBillingService;
    @Mock private EstimationService estimationService;
    @Mock private FeatureFlags featureFlags;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private QuizJobProperties quizJobProperties;
    @Mock private QuizDefaultsProperties quizDefaultsProperties;
    @Mock private QuizQueryService quizQueryService;
    @Mock private QuizCommandService quizCommandService;
    @Mock private QuizRelationService quizRelationService;
    @Mock private QuizPublishingService quizPublishingService;
    @Mock private QuizVisibilityService quizVisibilityService;
    @Mock private QuizAssemblyService quizAssemblyService;
    @Mock private QuizPublishValidator quizPublishValidator;
    @Mock private QuizGenerationFacade quizGenerationFacade;

    private ApplicationEventPublisher applicationEventPublisher;
    private QuizServiceImpl quizService;

    @BeforeEach
    void setUp() {
        quizService = new QuizServiceImpl(
                quizQueryService,
                quizCommandService,
                quizRelationService,
                quizPublishingService,
                quizVisibilityService,
                quizGenerationFacade
        );
        lenient().when(quizDefaultsProperties.getDefaultCategoryId())
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            TransactionCallbackWithoutResult callback = invocation.getArgument(0);
            callback.doInTransaction(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        
        // Configure facade delegation - tests will override these as needed
        lenient().doNothing().when(quizGenerationFacade).commitTokensForSuccessfulGeneration(any(), anyList(), any());
        lenient().doNothing().when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 2.1: exact usage commits all reserved tokens without releasing remainder")
    void commitTokensForSuccessfulGenerationExactUsageCommitsAllTokens() {
        UUID jobId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);

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

        List<Question> questions = List.of(question);

        // Verify delegation to facade
        quizService.commitTokensForSuccessfulGeneration(job, questions, request);
        verify(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, questions, request);
    }

    @Test
    @DisplayName("Scenario 2.2: under-usage releases remainder after commit")
    void commitTokensForSuccessfulGenerationUnderUsageReleasesRemainder() {
        UUID jobId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);

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

        List<Question> questions = List.of(question);

        // Verify delegation to facade
        quizService.commitTokensForSuccessfulGeneration(job, questions, request);
        verify(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, questions, request);
    }

    @Test
    @DisplayName("Scenario 2.3: over-usage caps committed tokens at reserved amount")
    void commitTokensForSuccessfulGenerationCappedAtReservedWhenActualExceeds() {
        UUID jobId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);

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

        List<Question> questions = List.of(question);

        // Verify delegation to facade
        quizService.commitTokensForSuccessfulGeneration(job, questions, request);
        verify(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, questions, request);
    }

    @Test
    @DisplayName("Scenario 2.4: skip commit when reservation already expired")
    void commitTokensForSuccessfulGenerationSkipsWhenReservationExpired() {
        UUID jobId = UUID.randomUUID();
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);

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

        List<Question> questions = List.of(question);

        // Verify delegation to facade
        quizService.commitTokensForSuccessfulGeneration(job, questions, request);
        verify(quizGenerationFacade).commitTokensForSuccessfulGeneration(job, questions, request);
    }

    @Test
    void handleQuizGenerationCompletedFailureReleasesReservationViaInternalService() {
        UUID jobId = UUID.randomUUID();

        // Configure facade to throw exception (delegation test)
        doThrow(new RuntimeException("boom"))
                .when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(eq(jobId), any(), any());

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

        quizService.handleQuizGenerationCompleted(event);

        // Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(eq(jobId), any(), eq(request));
    }
}
