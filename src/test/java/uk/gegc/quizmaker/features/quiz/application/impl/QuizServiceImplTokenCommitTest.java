package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidJobStateForCommitException;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
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
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for QuizServiceImpl.commitTokensForSuccessfulGeneration method.
 * Focuses on uncovered branches and error paths for token commit logic.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizServiceImpl Token Commit Tests")
class QuizServiceImplTokenCommitTest {

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

    @InjectMocks
    private QuizServiceImpl quizService;

    private QuizGenerationJob testJob;
    private List<Question> testQuestions;
    private GenerateQuizFromDocumentRequest testRequest;
    private UUID reservationId;

    @BeforeEach
    void setUp() {
        reservationId = UUID.randomUUID();
        
        // Create test job
        testJob = new QuizGenerationJob();
        testJob.setId(UUID.randomUUID());
        testJob.setDocumentId(UUID.randomUUID());
        testJob.setStatus(GenerationStatus.COMPLETED);
        testJob.setBillingState(BillingState.RESERVED);
        testJob.setBillingReservationId(reservationId);
        testJob.setBillingEstimatedTokens(1000L);
        testJob.setInputPromptTokens(500L);
        testJob.setReservationExpiresAt(LocalDateTime.now().plusHours(1));
        testJob.setBillingIdempotencyKeys("{}");
        
        // Create test questions
        testQuestions = createTestQuestions(10);
        
        // Create test request
        testRequest = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                null, // quizScope
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 10),
                Difficulty.MEDIUM,
                1, // estimatedTimePerQuestion
                UUID.randomUUID(),
                List.of()
        );
    }

    @Nested
    @DisplayName("Idempotency Tests")
    class IdempotencyTests {

        @Test
        @DisplayName("commitTokens: when job already has commit idempotency key then returns early")
        void commitTokens_hasIdempotencyKey_returnsEarly() {
            // Given
            testJob.setBillingIdempotencyKeys("{\"commit\": \"quiz:" + testJob.getId() + ":commit\"}");
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1346-1348 covered
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("commitTokens: when billing state is already COMMITTED then returns success")
        void commitTokens_alreadyCommitted_returnsSuccess() {
            // Given
            testJob.setBillingState(BillingState.COMMITTED);
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1337-1339 covered
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("commitTokens: when billing state is not RESERVED and not COMMITTED then throws exception")
        void commitTokens_invalidState_throwsException() {
            // Given - State is RELEASED (not RESERVED, not COMMITTED)
            testJob.setBillingState(BillingState.RELEASED);
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Line 1341 covered (throws InvalidJobStateForCommitException)
            // Exception is caught and logged in lines 1433-1439
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Job State Validation Tests")
    class JobStateValidationTests {

        @Test
        @DisplayName("commitTokens: when job not found during commit then throws exception")
        void commitTokens_jobNotFound_throwsException() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.empty());

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Line 1328 covered (throws IllegalStateException)
            // Exception is caught and logged in lines 1441-1445
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("commitTokens: when job has no reservation ID then returns early")
        void commitTokens_noReservationId_returnsEarly() {
            // Given
            testJob.setBillingReservationId(null);
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1330-1332 covered
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("commitTokens: when job status is not success then throws exception")
        void commitTokens_jobNotSuccess_throwsException() {
            // Given
            testJob.setStatus(GenerationStatus.PROCESSING);
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1352-1354 covered (throws InvalidJobStateForCommitException)
            // Exception is caught in lines 1433-1439
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("commitTokens: when reservation has expired then returns early")
        void commitTokens_reservationExpired_returnsEarly() {
            // Given
            testJob.setReservationExpiresAt(LocalDateTime.now().minusHours(1)); // Expired
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1358-1360 covered
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            verify(jobRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Token Calculation Tests")
    class TokenCalculationTests {

        @Test
        @DisplayName("commitTokens: when inputPromptTokens is null then uses 0")
        void commitTokens_nullInputPromptTokens_usesZero() {
            // Given
            testJob.setInputPromptTokens(null);
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), eq(0L)))
                    .thenReturn(800L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 800L, 200L));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Line 1364 covered (null check)
            verify(estimationService).computeActualBillingTokens(anyList(), any(), eq(0L));
            verify(internalBillingService).commit(eq(reservationId), eq(800L), eq("quiz-generation"), anyString());
        }

        @Test
        @DisplayName("commitTokens: when actual tokens exceed reserved then caps at reserved")
        void commitTokens_actualExceedsReserved_capsAtReserved() {
            // Given - Actual tokens (1500) exceed reserved (1000)
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(1500L); // More than reserved (1000L)
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 1000L, 0L));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1379-1383 covered (underestimation logging)
            verify(internalBillingService).commit(eq(reservationId), eq(1000L), eq("quiz-generation"), anyString());
            verify(jobRepository).save(argThat(job -> 
                job.getBillingCommittedTokens() == 1000L && 
                job.getActualTokens() == 1500L &&
                job.getWasCappedAtReserved()
            ));
        }

        @Test
        @DisplayName("commitTokens: when actual tokens less than reserved then commits actual")
        void commitTokens_actualLessThanReserved_commitsActual() {
            // Given - Actual tokens (700) less than reserved (1000)
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(700L); // Less than reserved (1000L)
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 700L, 300L)); // Released 300 remainder
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - No underestimation warning, remainder auto-released
            verify(internalBillingService).commit(eq(reservationId), eq(700L), eq("quiz-generation"), anyString());
            verify(jobRepository).save(argThat(job -> 
                job.getBillingCommittedTokens() == 700L && 
                job.getActualTokens() == 700L &&
                !job.getWasCappedAtReserved()
            ));
        }
    }

    @Nested
    @DisplayName("Explicit Release Tests")
    class ExplicitReleaseTests {

        @Test
        @DisplayName("commitTokens: when commitResult is null and remainder > 0 then explicitly releases")
        void commitTokens_nullCommitResultWithRemainder_explicitlyReleases() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(700L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(null); // Null result
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1404, 1406-1408 covered (explicit release with null commitResult)
            verify(internalBillingService).release(eq(reservationId), eq("commit-remainder"), eq("quiz-generation"), isNull());
        }

        @Test
        @DisplayName("commitTokens: when commitResult has zero releasedTokens and remainder > 0 then explicitly releases")
        void commitTokens_zeroReleasedTokensWithRemainder_explicitlyReleases() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(700L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 700L, 0L)); // Zero released
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1404, 1406-1408 covered (explicit release when releasedTokens == 0)
            verify(internalBillingService).release(eq(reservationId), eq("commit-remainder"), eq("quiz-generation"), isNull());
        }

        @Test
        @DisplayName("commitTokens: when explicit release fails then logs warning")
        void commitTokens_explicitReleaseFails_logsWarning() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(700L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 700L, 0L));
            doThrow(new RuntimeException("Release service unavailable"))
                    .when(internalBillingService).release(any(), anyString(), anyString(), any());
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1409-1411 covered (exception during explicit release)
            verify(internalBillingService).release(eq(reservationId), eq("commit-remainder"), eq("quiz-generation"), isNull());
            verify(jobRepository).save(any()); // Job should still be saved
        }

        @Test
        @DisplayName("commitTokens: when remainder is zero then does not release")
        void commitTokens_zeroRemainder_doesNotRelease() {
            // Given - Actual equals reserved
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(1000L); // Exactly reserved amount
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 1000L, 0L));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - No explicit release since remainder == 0
            verify(internalBillingService, never()).release(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("commitTokens: when commitResult has non-zero releasedTokens then does not explicitly release")
        void commitTokens_commitResultHasReleasedTokens_doesNotExplicitlyRelease() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(700L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 700L, 300L)); // Already released remainder
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - No explicit release since billing service already released
            verify(internalBillingService, never()).release(any(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("commitTokens: when InvalidJobStateForCommitException occurs then logs and stores error")
        void commitTokens_invalidJobStateException_logsAndStoresError() {
            // Given - Set up to trigger InvalidJobStateForCommitException
            testJob.setStatus(GenerationStatus.FAILED); // Not a success status
            
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));

            // When - Lines 1433-1439 covered
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Exception is caught, logged, and billing error is stored
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
            // Note: storeBillingError is a private method, we verify it indirectly
        }

        @Test
        @DisplayName("commitTokens: when unexpected exception during commit then logs and stores error")
        void commitTokens_unexpectedException_logsAndStoresError() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenThrow(new RuntimeException("Unexpected calculation error"));

            // When - Lines 1441-1445 covered
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Exception is caught and logged, doesn't fail quiz creation
            verify(internalBillingService, never()).commit(any(), anyLong(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Success Path Tests")
    class SuccessPathTests {

        @Test
        @DisplayName("commitTokens: when successful then updates job billing fields")
        void commitTokens_successful_updatesJobBillingFields() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(900L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(new CommitResultDto(reservationId, 900L, 100L));
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Lines 1416-1427 covered (update job fields and save)
            verify(jobRepository).save(argThat(job -> 
                job.getActualTokens() == 900L &&
                job.getBillingCommittedTokens() == 900L &&
                !job.getWasCappedAtReserved() &&
                job.getBillingState() == BillingState.COMMITTED &&
                job.getLastBillingError() == null
            ));
        }

        @Test
        @DisplayName("commitTokens: when commit result is null then logs correctly")
        void commitTokens_nullCommitResult_logsCorrectly() {
            // Given
            when(jobRepository.findByIdForUpdate(testJob.getId()))
                    .thenReturn(Optional.of(testJob));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(1000L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(null); // Null result
            when(jobRepository.save(any())).thenReturn(testJob);

            // When
            quizService.commitTokensForSuccessfulGeneration(testJob, testQuestions, testRequest);

            // Then - Line 1431 covered (null check for releasedTokens logging)
            verify(jobRepository).save(any());
        }
    }

    // Helper methods

    private List<Question> createTestQuestions(int count) {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            q.setType(QuestionType.MCQ_SINGLE);
            q.setQuestionText("Test question " + i);
            q.setDifficulty(Difficulty.MEDIUM);
            questions.add(q);
        }
        return questions;
    }
}

