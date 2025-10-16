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
 * Comprehensive tests for QuizServiceImpl.cancelGenerationJob method.
 * Focuses on covering all billing scenarios and edge cases.
 */
@ExtendWith(MockitoExtension.class)
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

    @InjectMocks
    private QuizServiceImpl quizService;

    private QuizGenerationJob testJob;
    private UUID jobId;
    private UUID reservationId;
    private String username = "testuser";

    @BeforeEach
    void setUp() {
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
    }

    @Nested
    @DisplayName("Terminal Status Tests")
    class TerminalStatusTests {

        @Test
        @DisplayName("cancelJob: when job is already in terminal state then throws ValidationException")
        void cancelJob_terminalState_throwsValidationException() {
            // Given - Job is already completed
            testJob.setStatus(GenerationStatus.COMPLETED);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);

            // When & Then - Line 620 covered
            assertThatThrownBy(() -> quizService.cancelGenerationJob(jobId, username))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Cannot cancel job that is already in terminal state");
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
            testJob.setActualTokens(null); // Line 640 null branch
            
            CommitResultDto commitResult = new CommitResultDto(reservationId, 100L, 900L);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(internalBillingService.commit(any(), eq(100L), anyString(), anyString()))
                    .thenReturn(commitResult);
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Line 640 covered (null actualTokens defaults to 0)
            verify(internalBillingService).commit(eq(reservationId), eq(100L), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when tokensToCommit is 0 then releases instead")
        void cancelJob_tokensToCommitZero_releases() {
            // Given - Work started but no actual tokens, and minStartFeeTokens is 0
            testJob.setHasStartedAiCalls(true);
            testJob.setActualTokens(0L);
            
            when(cancellationConfig.getMinStartFeeTokens()).thenReturn(0L); // Results in tokensToCommit = 0
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Lines 668-678 covered (tokensToCommit == 0 path)
            verify(billingService).release(eq(reservationId), contains("no work completed"), anyString(), anyString());
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when commitResult has no released tokens then logs appropriately")
        void cancelJob_commitResultNoReleasedTokens_logsCorrectly() {
            // Given
            testJob.setHasStartedAiCalls(true);
            testJob.setActualTokens(500L);
            
            CommitResultDto commitResult = new CommitResultDto(reservationId, 500L, 0L); // No released tokens
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Line 663 covered (releasedTokens == 0 branch)
            verify(internalBillingService).commit(any(), anyLong(), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when commit/release fails then catches and logs error")
        void cancelJob_commitFails_catchesAndLogsError() {
            // Given
            testJob.setHasStartedAiCalls(true);
            testJob.setActualTokens(500L);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Billing service unavailable"));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Lines 681-685 covered (exception handling)
            verify(jobRepository, times(2)).save(argThat(job -> 
                job.getLastBillingError() != null &&
                job.getLastBillingError().contains("Failed to process billing on cancel")
            ));
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Billing Scenarios - No Work Started")
    class NoWorkStartedBillingTests {

        @Test
        @DisplayName("cancelJob: when no work started then releases reservation")
        void cancelJob_noWorkStarted_releasesReservation() {
            // Given - No work started
            testJob.setHasStartedAiCalls(false);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Release path taken
            verify(billingService).release(eq(reservationId), eq("Job cancelled by user"), anyString(), anyString());
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when release fails then catches and logs error")
        void cancelJob_releaseFails_catchesAndLogsError() {
            // Given
            testJob.setHasStartedAiCalls(false);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            doThrow(new RuntimeException("Release service unavailable"))
                    .when(billingService).release(any(), anyString(), anyString(), anyString());
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Lines 701-704 covered (exception handling for release)
            verify(jobRepository, times(2)).save(argThat(job -> 
                job.getLastBillingError() != null &&
                job.getLastBillingError().contains("Failed to release reservation")
            ));
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when commitOnCancel is false then releases reservation")
        void cancelJob_commitOnCancelFalse_releasesReservation() {
            // Given
            testJob.setHasStartedAiCalls(true);
            when(cancellationConfig.isCommitOnCancel()).thenReturn(false); // Line 637 branch
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Takes "no work started" path (lines 687-706)
            verify(billingService).release(eq(reservationId), eq("Job cancelled by user"), anyString(), anyString());
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Billing Edge Cases")
    class BillingEdgeCaseTests {

        @Test
        @DisplayName("cancelJob: when no billing reservation then skips billing logic")
        void cancelJob_noReservation_skipsBillingLogic() {
            // Given - No billing reservation
            testJob.setBillingReservationId(null); // Line 633 branch
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - No billing calls made
            verify(billingService, never()).release(any(), anyString(), anyString(), anyString());
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when billing state is not RESERVED then skips billing logic")
        void cancelJob_billingNotReserved_skipsBillingLogic() {
            // Given - Billing state is COMMITTED
            testJob.setBillingState(BillingState.COMMITTED); // Line 633 branch
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - No billing calls made
            verify(billingService, never()).release(any(), anyString(), anyString(), anyString());
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when billing feature is disabled then skips commit")
        void cancelJob_billingDisabled_skipsBillingLogic() {
            // Given
            testJob.setHasStartedAiCalls(true);
            when(featureFlags.isBilling()).thenReturn(false); // Line 637 branch
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Takes "no work started" path
            verify(billingService).release(eq(reservationId), eq("Job cancelled by user"), anyString(), anyString());
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when actualTokens exceed minStartFee then commits actual")
        void cancelJob_actualExceedsMinFee_commitsActual() {
            // Given - Actual tokens (500) exceed minStartFee (100)
            testJob.setHasStartedAiCalls(true);
            testJob.setActualTokens(500L);
            
            CommitResultDto commitResult = new CommitResultDto(reservationId, 500L, 500L);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(internalBillingService.commit(any(), eq(500L), anyString(), anyString()))
                    .thenReturn(commitResult);
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Commits actual tokens (500), not minStartFee (100)
            verify(internalBillingService).commit(eq(reservationId), eq(500L), anyString(), anyString());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: when actualTokens less than minStartFee then commits minStartFee")
        void cancelJob_actualLessThanMinFee_commitsMinFee() {
            // Given - Actual tokens (50) less than minStartFee (100)
            testJob.setHasStartedAiCalls(true);
            testJob.setActualTokens(50L);
            
            CommitResultDto commitResult = new CommitResultDto(reservationId, 100L, 900L);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(internalBillingService.commit(any(), eq(100L), anyString(), anyString()))
                    .thenReturn(commitResult);
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then - Commits minStartFee (100), not actual (50)
            verify(internalBillingService).commit(eq(reservationId), eq(100L), anyString(), anyString());
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Success Path Tests")
    class SuccessPathTests {

        @Test
        @DisplayName("cancelJob: successful cancellation with work started and commit")
        void cancelJob_successWithWorkStarted_commitsAndReturnsStatus() {
            // Given
            testJob.setHasStartedAiCalls(true);
            testJob.setActualTokens(300L);
            
            CommitResultDto commitResult = new CommitResultDto(reservationId, 300L, 700L);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            verify(jobService).cancelJob(jobId, username);
            verify(internalBillingService).commit(any(), anyLong(), anyString(), anyString());
            verify(jobRepository, times(2)).save(any());
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("cancelJob: successful cancellation without work started")
        void cancelJob_successWithoutWorkStarted_releasesAndReturnsStatus() {
            // Given
            testJob.setHasStartedAiCalls(false);
            
            when(jobService.getJobByIdAndUsername(jobId, username)).thenReturn(testJob);
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(testJob));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            QuizGenerationStatus result = quizService.cancelGenerationJob(jobId, username);

            // Then
            verify(jobService).cancelJob(jobId, username);
            verify(billingService).release(any(), anyString(), anyString(), anyString());
            verify(jobRepository, times(2)).save(any());
            assertThat(result).isNotNull();
        }
    }
}

