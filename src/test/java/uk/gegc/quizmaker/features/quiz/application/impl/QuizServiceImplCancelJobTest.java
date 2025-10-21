package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationStatus;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for QuizServiceImpl.cancelGenerationJob delegation.
 * 
 * After refactoring, QuizServiceImpl delegates to QuizGenerationFacade.
 * These tests verify that the delegation works correctly by mocking the facade responses.
 * The actual implementation logic is tested in QuizGenerationFacadeImplComplexFlowsTest.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("QuizServiceImpl Cancel Job Tests")
class QuizServiceImplCancelJobTest {

    @Mock
    private QuizRepository quizRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private QuizMapper quizMapper;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QuestionHandlerFactory questionHandlerFactory;
    @Mock
    private QuizGenerationJobRepository jobRepository;
    @Mock
    private QuizGenerationJobService jobService;
    @Mock
    private AiQuizGenerationService aiQuizGenerationService;
    @Mock
    private DocumentProcessingService documentProcessingService;
    @Mock
    private QuizHashCalculator quizHashCalculator;
    @Mock
    private BillingService billingService;
    @Mock
    private InternalBillingService internalBillingService;
    @Mock
    private EstimationService estimationService;
    @Mock
    private AppPermissionEvaluator permissionEvaluator;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private QuizJobProperties quizJobProperties;
    @Mock
    private QuizJobProperties.Cancellation cancellationConfig;
    @Mock
    private QuizQueryService quizQueryService;
    @Mock
    private QuizCommandService quizCommandService;
    @Mock
    private QuizRelationService quizRelationService;
    @Mock
    private QuizPublishingService quizPublishingService;
    @Mock
    private QuizVisibilityService quizVisibilityService;
    @Mock
    private QuizGenerationFacade quizGenerationFacade;

    private QuizServiceImpl quizService;

    private QuizGenerationJob testJob;
    private UUID jobId;
    private UUID reservationId;
    private String username = "testuser";

    @BeforeEach
    void setUp() {
        // Create QuizServiceImpl with new refactored dependencies
        quizService = new QuizServiceImpl(
                quizQueryService,
                quizCommandService,
                quizRelationService,
                quizPublishingService,
                quizVisibilityService,
                quizGenerationFacade
        );
        
        jobId = UUID.randomUUID();
        reservationId = UUID.randomUUID();
        
        User testUser = new User();
        testUser.setUsername(username);
        
        testJob = new QuizGenerationJob();
        testJob.setId(jobId);
        testJob.setUser(testUser);
        testJob.setDocumentId(UUID.randomUUID());
        testJob.setStatus(GenerationStatus.PROCESSING);
        testJob.setBillingState(BillingState.RESERVED);
        testJob.setBillingReservationId(reservationId);
        testJob.setBillingEstimatedTokens(1000L);
        testJob.setBillingIdempotencyKeys("{}");
        
        // Default mocks
        lenient().when(quizJobProperties.getCancellation()).thenReturn(cancellationConfig);
        lenient().when(cancellationConfig.isCommitOnCancel()).thenReturn(true);
        lenient().when(cancellationConfig.getMinStartFeeTokens()).thenReturn(100L);
        lenient().when(featureFlags.isBilling()).thenReturn(true);
        
        // Configure facade to delegate - we'll override this in individual tests
        lenient().when(quizGenerationFacade.cancelGenerationJob(any(UUID.class), anyString()))
                .thenAnswer(invocation -> QuizGenerationStatus.fromEntity(testJob, featureFlags.isBilling()));
    }

    @Nested
    @DisplayName("Terminal Status Tests")
    class TerminalStatusTests {

        @Test
        @DisplayName("cancelJob: when job is already in terminal state then throws ValidationException")
        void cancelJob_terminalState_throwsValidationException() {
            // Given - Job is already completed, facade will throw exception
            testJob.setStatus(GenerationStatus.COMPLETED);
            
            when(quizGenerationFacade.cancelGenerationJob(jobId, username))
                    .thenThrow(new ValidationException("Cannot cancel job that is already in terminal state: COMPLETED"));

            // When & Then - Verifies delegation works and exception propagates
            assertThatThrownBy(() -> quizService.cancelGenerationJob(jobId, username))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Cannot cancel job that is already in terminal state");
            
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }
    }

    @Nested
    @DisplayName("Billing Scenarios - Work Started")
    class WorkStartedBillingTests {

        @Test
        @DisplayName("cancelJob: when work started with null actualTokens then uses 0")
        void cancelJob_workStartedNullActualTokens_usesZero() {
            // Given - Work started but actualTokens is null
            testJob.setHasStartedAiCalls(true);
            testJob.setActualTokens(null);
            testJob.setStatus(GenerationStatus.CANCELLED);
            
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Verifies delegation works
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when tokensToCommit is 0 then releases instead")
        void cancelJob_tokensToCommitZero_releases() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when commitResult has no released tokens then logs appropriately")
        void cancelJob_commitResultNoReleasedTokens_logsCorrectly() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when commit/release fails then catches and logs error")
        void cancelJob_commitFails_catchesAndLogsError() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }
    }

    @Nested
    @DisplayName("Billing Scenarios - No Work Started")
    class NoWorkStartedBillingTests {

        @Test
        @DisplayName("cancelJob: when no work started then releases reservation")
        void cancelJob_noWorkStarted_releasesReservation() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when release fails then catches and logs error")
        void cancelJob_releaseFails_catchesAndLogsError() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when commitOnCancel is false then releases reservation")
        void cancelJob_commitOnCancelFalse_releasesReservation() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }
    }

    @Nested
    @DisplayName("Billing Edge Cases")
    class BillingEdgeCaseTests {

        @Test
        @DisplayName("cancelJob: when no billing reservation then skips billing logic")
        void cancelJob_noReservation_skipsBillingLogic() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when billing state is not RESERVED then skips billing logic")
        void cancelJob_billingNotReserved_skipsBillingLogic() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when billing feature is disabled then skips commit")
        void cancelJob_billingDisabled_skipsBillingLogic() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when actualTokens exceed minStartFee then commits actual")
        void cancelJob_actualExceedsMinFee_commitsActual() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: when actualTokens less than minStartFee then commits minStartFee")
        void cancelJob_actualLessThanMinFee_commitsMinFee() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }
    }

    @Nested
    @DisplayName("Success Path Tests")
    class SuccessPathTests {

        @Test
        @DisplayName("cancelJob: successful cancellation with work started and commit")
        void cancelJob_successWithWorkStarted_commitsAndReturnsStatus() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }

        @Test
        @DisplayName("cancelJob: successful cancellation without work started")
        void cancelJob_successWithoutWorkStarted_releasesAndReturnsStatus() {
            // Given
            testJob.setStatus(GenerationStatus.CANCELLED);
            QuizGenerationStatus expectedStatus = QuizGenerationStatus.fromEntity(testJob, true);
            when(quizGenerationFacade.cancelGenerationJob(jobId, username)).thenReturn(expectedStatus);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(quizGenerationFacade).cancelGenerationJob(jobId, username);
        }
    }
}

