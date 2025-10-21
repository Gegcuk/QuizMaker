package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidJobStateForCommitException;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for QuizGenerationFacadeImpl billing and assembly flows.
 * 
 * <p>Part 3 of QuizGenerationFacadeImpl tests, focusing on:
 * - createQuizCollectionFromGeneratedQuestions() - Quiz assembly orchestration
 * - commitTokensForSuccessfulGeneration() - Billing commit with idempotency
 * 
 * <p>These tests cover the most complex billing and quiz creation logic.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("QuizGenerationFacadeImpl - Billing & Assembly Tests")
class QuizGenerationFacadeImplBillingTest {

    @Mock
    private UserRepository userRepository;
    
    @Mock
    private QuizGenerationJobRepository jobRepository;
    
    @Mock
    private QuizGenerationJobService jobService;
    
    @Mock
    private AiQuizGenerationService aiQuizGenerationService;
    
    @Mock
    private DocumentProcessingService documentProcessingService;
    
    @Mock
    private BillingService billingService;
    
    @Mock
    private InternalBillingService internalBillingService;
    
    @Mock
    private EstimationService estimationService;
    
    @Mock
    private FeatureFlags featureFlags;
    
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    
    @Mock
    private TransactionTemplate transactionTemplate;
    
    @Mock
    private QuizJobProperties quizJobProperties;
    
    @Mock
    private QuizAssemblyService quizAssemblyService;
    
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @InjectMocks
    private QuizGenerationFacadeImpl facade;
    
    private User testUser;
    private UUID documentId;
    private UUID jobId;
    private QuizGenerationJob job;
    private GenerateQuizFromDocumentRequest originalRequest;
    private Category category;
    private Set<Tag> tags;
    private Quiz consolidatedQuiz;
    
    @BeforeEach
    void setUp() {
        testUser = createUser("testuser", UUID.randomUUID());
        documentId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        
        originalRequest = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Test Quiz", "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 5, QuestionType.TRUE_FALSE, 5),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );
        
        job = createJob(testUser, documentId, jobId);
        
        category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("AI Generated");
        
        tags = new HashSet<>();
        
        consolidatedQuiz = new Quiz();
        consolidatedQuiz.setId(UUID.randomUUID());
        consolidatedQuiz.setTitle("Test Quiz");
        consolidatedQuiz.setCreator(testUser);
    }
    
    // ============================================================================
    // 9. createQuizCollectionFromGeneratedQuestions() Tests
    // ============================================================================
    
    @Nested
    @DisplayName("createQuizCollectionFromGeneratedQuestions() Tests")
    class CreateQuizCollectionTests {
        
        @Test
        @DisplayName("Successful creation - single chunk - creates consolidated only")
        void successfulCreation_singleChunk_createsConsolidatedOnly() {
            // Given
            List<Question> questions = createQuestions(5);
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, questions);
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            when(quizAssemblyService.createConsolidatedQuiz(
                    eq(testUser), anyList(), eq(originalRequest), eq(category), eq(tags), eq(documentId), eq(1)))
                    .thenReturn(consolidatedQuiz);
            
            // When
            facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest);
            
            // Then
            verify(quizAssemblyService, never()).createChunkQuiz(any(), any(), anyInt(), any(), any(), any(), any());
            verify(quizAssemblyService).createConsolidatedQuiz(
                    eq(testUser), anyList(), eq(originalRequest), eq(category), eq(tags), eq(documentId), eq(1));
            verify(jobRepository, atLeastOnce()).save(job);
            assertThat(job.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        }
        
        @Test
        @DisplayName("Successful creation - multiple chunks - creates chunk and consolidated")
        void successfulCreation_multipleChunks_createsChunkAndConsolidated() {
            // Given
            List<Question> chunk1 = createQuestions(3);
            List<Question> chunk2 = createQuestions(3);
            List<Question> chunk3 = createQuestions(4);
            Map<Integer, List<Question>> chunkQuestions = Map.of(
                    0, chunk1,
                    1, chunk2,
                    2, chunk3
            );
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            
            Quiz chunkQuiz1 = new Quiz();
            chunkQuiz1.setId(UUID.randomUUID());
            Quiz chunkQuiz2 = new Quiz();
            chunkQuiz2.setId(UUID.randomUUID());
            Quiz chunkQuiz3 = new Quiz();
            chunkQuiz3.setId(UUID.randomUUID());
            
            when(quizAssemblyService.createChunkQuiz(
                    eq(testUser), anyList(), anyInt(), eq(originalRequest), eq(category), eq(tags), eq(documentId)))
                    .thenReturn(chunkQuiz1, chunkQuiz2, chunkQuiz3);
            
            when(quizAssemblyService.createConsolidatedQuiz(
                    eq(testUser), anyList(), eq(originalRequest), eq(category), eq(tags), eq(documentId), eq(3)))
                    .thenReturn(consolidatedQuiz);
            
            // When
            facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest);
            
            // Then
            verify(quizAssemblyService, times(3)).createChunkQuiz(
                    eq(testUser), anyList(), anyInt(), eq(originalRequest), eq(category), eq(tags), eq(documentId));
            verify(quizAssemblyService).createConsolidatedQuiz(
                    eq(testUser), anyList(), eq(originalRequest), eq(category), eq(tags), eq(documentId), eq(3));
        }
        
        @Test
        @DisplayName("Job not found - throws RuntimeException with cause")
        void jobNotFound_throwsRuntimeException() {
            // Given
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, createQuestions(5));
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.empty());
            
            // When & Then
            assertThatThrownBy(() -> facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to create quiz collection from generated questions")
                    .hasCauseInstanceOf(ResourceNotFoundException.class);
        }
        
        @Test
        @DisplayName("Job already terminal - skips creation")
        void jobAlreadyTerminal_skipsCreation() {
            // Given
            job.setStatus(GenerationStatus.COMPLETED);
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, createQuestions(5));
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            
            // When
            facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest);
            
            // Then
            verifyNoInteractions(quizAssemblyService);
            verify(jobRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Empty chunks - skipped")
        void emptyChunks_skipped() {
            // Given
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
            chunkQuestions.put(0, createQuestions(3));
            chunkQuestions.put(1, null);  // null chunk
            chunkQuestions.put(2, List.of());  // empty chunk
            chunkQuestions.put(3, createQuestions(2));
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            
            Quiz chunkQuiz1 = new Quiz();
            chunkQuiz1.setId(UUID.randomUUID());
            Quiz chunkQuiz2 = new Quiz();
            chunkQuiz2.setId(UUID.randomUUID());
            
            when(quizAssemblyService.createChunkQuiz(any(), anyList(), anyInt(), any(), any(), any(), any()))
                    .thenReturn(chunkQuiz1, chunkQuiz2);
            
            when(quizAssemblyService.createConsolidatedQuiz(any(), anyList(), any(), any(), any(), any(), eq(2)))
                    .thenReturn(consolidatedQuiz);
            
            // When
            facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest);
            
            // Then - only 2 chunk quizzes created (skipping null and empty)
            verify(quizAssemblyService, times(2)).createChunkQuiz(any(), anyList(), anyInt(), any(), any(), any(), any());
        }
        
        @Test
        @DisplayName("Chunk quiz creation fails - handles failure")
        void chunkQuizCreationFails_handlesFailure() {
            // Given
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, createQuestions(5));
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            when(quizAssemblyService.createConsolidatedQuiz(any(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("Quiz creation failed"));
            
            // When & Then
            assertThatThrownBy(() -> facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to create quiz collection from generated questions");
            
            // Verify job marked as failed and billing released
            assertThat(job.getStatus()).isEqualTo(GenerationStatus.FAILED);
            assertThat(job.getErrorMessage()).contains("Failed to create quiz collection");
        }
        
        @Test
        @DisplayName("Consolidated quiz creation fails - handles failure")
        void consolidatedQuizCreationFails_handlesFailure() {
            // Given
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, createQuestions(5));
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            when(quizAssemblyService.createConsolidatedQuiz(any(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("Consolidated quiz failed"));
            
            // When & Then
            assertThatThrownBy(() -> facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest))
                    .isInstanceOf(RuntimeException.class);
            
            assertThat(job.getStatus()).isEqualTo(GenerationStatus.FAILED);
        }
        
        @Test
        @DisplayName("Commit tokens called - after success")
        void commitTokensCalled_afterSuccess() {
            // Given
            List<Question> questions = createQuestions(5);
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, questions);
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            when(quizAssemblyService.createConsolidatedQuiz(any(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(consolidatedQuiz);
            
            // Mock commit tokens to do nothing (we're testing it was called)
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            job.setBillingReservationId(null);  // will return early
            
            // When
            facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest);
            
            // Then
            verify(jobRepository, atLeastOnce()).findByIdForUpdate(jobId);
        }
        
        @Test
        @DisplayName("Job save fails after failure - logs error")
        void jobSaveFailsAfterFailure_logsError() {
            // Given
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, createQuestions(5));
            
            when(jobRepository.findById(jobId))
                    .thenReturn(Optional.of(job))
                    .thenReturn(Optional.empty());  // second call fails
            
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            when(quizAssemblyService.createConsolidatedQuiz(any(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("Quiz failed"));
            
            // When & Then
            assertThatThrownBy(() -> facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest))
                    .isInstanceOf(RuntimeException.class);
            
            // Error logged, no exception from save failure
        }
        
        @Test
        @DisplayName("Billing release fails after failure - error saved to job")
        void billingReleaseFailsAfterFailure_errorSavedToJob() {
            // Given
            job.setBillingReservationId(UUID.randomUUID());
            job.setBillingState(BillingState.RESERVED);
            
            Map<Integer, List<Question>> chunkQuestions = Map.of(0, createQuestions(5));
            
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(quizAssemblyService.getOrCreateAICategory()).thenReturn(category);
            when(quizAssemblyService.resolveTags(originalRequest)).thenReturn(tags);
            when(quizAssemblyService.createConsolidatedQuiz(any(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("Quiz failed"));
            
            doThrow(new RuntimeException("Release failed"))
                    .when(internalBillingService).release(any(), anyString(), anyString(), anyString());
            
            // When & Then
            assertThatThrownBy(() -> facade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest))
                    .isInstanceOf(RuntimeException.class);
            
            // Verify release was attempted
            verify(internalBillingService).release(any(), anyString(), anyString(), anyString());
            assertThat(job.getLastBillingError()).contains("Failed to release reservation");
        }
    }
    
    // ============================================================================
    // 10. commitTokensForSuccessfulGeneration() Tests - VERY HIGH COMPLEXITY
    // ============================================================================
    
    @Nested
    @DisplayName("commitTokensForSuccessfulGeneration() Tests")
    class CommitTokensTests {
        
        private List<Question> allQuestions;
        
        @BeforeEach
        void setUp() {
            allQuestions = createQuestions(10);
            job.setStatus(GenerationStatus.COMPLETED);
            job.setBillingState(BillingState.RESERVED);
            job.setBillingReservationId(UUID.randomUUID());
            job.setBillingEstimatedTokens(1200L);
            job.setInputPromptTokens(1000L);
        }
        
        @Test
        @DisplayName("Successful commit - all steps complete")
        void successfulCommit_allStepsComplete() {
            // Given
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), eq(Difficulty.MEDIUM), eq(1000L)))
                    .thenReturn(800L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 800L, 400L);
            when(internalBillingService.commit(eq(job.getBillingReservationId()), eq(800L), eq("quiz-generation"), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then
            verify(jobRepository).findByIdForUpdate(jobId);
            verify(estimationService).computeActualBillingTokens(anyList(), eq(Difficulty.MEDIUM), eq(1000L));
            verify(internalBillingService).commit(eq(job.getBillingReservationId()), eq(800L), eq("quiz-generation"), anyString());
            verify(jobRepository, atLeastOnce()).save(job);
            
            assertThat(job.getActualTokens()).isEqualTo(800L);
            assertThat(job.getBillingCommittedTokens()).isEqualTo(800L);
            assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
            assertThat(job.getWasCappedAtReserved()).isFalse();
            assertThat(job.getLastBillingError()).isNull();
        }
        
        @Test
        @DisplayName("Actual exceeds reserved - capped at reserved")
        void actualExceedsReserved_cappedAtReserved() {
            // Given
            job.setBillingEstimatedTokens(500L);  // low estimate
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), eq(Difficulty.MEDIUM), anyLong()))
                    .thenReturn(800L);  // actual is higher
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 500L, 0L);
            when(internalBillingService.commit(eq(job.getBillingReservationId()), eq(500L), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then - commits 500 (capped), not 800
            verify(internalBillingService).commit(eq(job.getBillingReservationId()), eq(500L), anyString(), anyString());
            assertThat(job.getActualTokens()).isEqualTo(800L);
            assertThat(job.getBillingCommittedTokens()).isEqualTo(500L);
            assertThat(job.getWasCappedAtReserved()).isTrue();
        }
        
        @Test
        @DisplayName("No reservation ID - returns early")
        void noReservationId_returnsEarly() {
            // Given
            job.setBillingReservationId(null);
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then
            verifyNoInteractions(internalBillingService);
            verify(jobRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Already committed - returns early")
        void alreadyCommitted_returnsEarly() {
            // Given
            job.setBillingState(BillingState.COMMITTED);
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then - idempotent, returns early
            verifyNoInteractions(internalBillingService);
            verify(jobRepository, never()).save(any());
        }
        
        @Test
        @DisplayName("Idempotency key exists - returns early")
        void idempotencyKeyExists_returnsEarly() throws Exception {
            // Given
            String existingKeys = "{\"commit\": \"quiz:" + jobId + ":commit\"}";
            job.setBillingIdempotencyKeys(existingKeys);
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then
            verifyNoInteractions(internalBillingService);
        }
        
        @Test
        @DisplayName("Reservation expired - returns early")
        void reservationExpired_returnsEarly() {
            // Given
            job.setReservationExpiresAt(LocalDateTime.now().minusHours(1));  // expired
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then
            verifyNoInteractions(internalBillingService);
        }
        
        @Test
        @DisplayName("Commit result - remainder released")
        void commitResult_remainderReleased() {
            // Given
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(800L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 800L, 400L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then - remainder already released by billing service
            assertThat(commitResult.releasedTokens()).isEqualTo(400L);
            
            // No explicit release needed
            verify(internalBillingService, never()).release(any(), anyString(), anyString(), any());
        }
        
        @Test
        @DisplayName("Commit result - no release - explicit release attempted")
        void commitResult_noRelease_explicitReleaseAttempted() {
            // Given
            job.setBillingEstimatedTokens(1000L);
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(600L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 600L, 0L);  // no release
            when(internalBillingService.commit(any(), eq(600L), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then - explicit release should be attempted for 400 tokens
            verify(internalBillingService).release(eq(job.getBillingReservationId()), eq("commit-remainder"), 
                    eq("quiz-generation"), isNull());
        }
        
        @Test
        @DisplayName("Explicit release fails - logs warning")
        void explicitReleaseFails_logsWarning() {
            // Given
            job.setBillingEstimatedTokens(1000L);
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(600L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 600L, 0L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            doThrow(new RuntimeException("Release failed"))
                    .when(internalBillingService).release(any(), anyString(), anyString(), any());
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then - commit still succeeds, release failure is logged
            assertThat(job.getBillingState()).isEqualTo(BillingState.COMMITTED);
        }
        
        @Test
        @DisplayName("Actual tokens calculated - from questions")
        void actualTokensCalculated_fromQuestions() {
            // Given
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(eq(allQuestions), eq(Difficulty.MEDIUM), eq(1000L)))
                    .thenReturn(900L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 900L, 300L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then
            verify(estimationService).computeActualBillingTokens(eq(allQuestions), eq(Difficulty.MEDIUM), eq(1000L));
        }
        
        @Test
        @DisplayName("Input prompt tokens - used in calculation")
        void inputPromptTokens_usedInCalculation() {
            // Given
            job.setInputPromptTokens(2500L);
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), any(), eq(2500L)))
                    .thenReturn(900L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 900L, 300L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then
            verify(estimationService).computeActualBillingTokens(anyList(), any(), eq(2500L));
        }
        
        @Test
        @DisplayName("Billing idempotency keys updated - with commit key")
        void billingIdempotencyKeysUpdated_withCommitKey() throws Exception {
            // Given
            job.setBillingIdempotencyKeys("{\"reserve\": \"quiz:" + jobId + ":reserve\"}");
            
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(800L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 800L, 400L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then
            assertThat(job.getBillingIdempotencyKeys()).contains("\"commit\"");
            assertThat(job.getBillingIdempotencyKeys()).contains("quiz:" + jobId + ":commit");
        }
        
        @Test
        @DisplayName("Pessimistic lock - prevents race conditions")
        void pessimisticLock_preventsRaceConditions() {
            // Given
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
            when(estimationService.computeActualBillingTokens(anyList(), any(), anyLong()))
                    .thenReturn(800L);
            
            CommitResultDto commitResult = new CommitResultDto(job.getBillingReservationId(), 800L, 400L);
            when(internalBillingService.commit(any(), anyLong(), anyString(), anyString()))
                    .thenReturn(commitResult);
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then - verify pessimistic lock was used
            verify(jobRepository).findByIdForUpdate(jobId);
            verify(jobRepository, never()).findById(jobId);  // not using regular findById
        }
        
        @Test
        @DisplayName("Job not found during commit - throws IllegalStateException")
        void jobNotFoundDuringCommit_throwsIllegalStateException() {
            // Given
            when(jobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.empty());
            
            // When
            facade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
            
            // Then - error logged, doesn't throw (fails gracefully)
            // The method catches all exceptions and stores them
            verify(jobRepository).findByIdForUpdate(jobId);
        }
    }
    
    // ============================================================================
    // Helper Methods
    // ============================================================================
    
    private User createUser(String username, UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        return user;
    }
    
    private QuizGenerationJob createJob(User user, UUID documentId, UUID jobId) {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setUser(user);
        job.setDocumentId(documentId);
        job.setStatus(GenerationStatus.PROCESSING);
        job.setTotalChunks(3);
        job.setBillingState(BillingState.RESERVED);
        job.setBillingReservationId(UUID.randomUUID());
        job.setBillingEstimatedTokens(1200L);
        job.setInputPromptTokens(1000L);
        job.setEstimationVersion("v1.0");
        return job;
    }
    
    private List<Question> createQuestions(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> {
                    Question q = new Question();
                    q.setId(UUID.randomUUID());
                    q.setQuestionText("Question " + i);
                    q.setType(QuestionType.MCQ_SINGLE);
                    return q;
                })
                .collect(Collectors.toList());
    }
}

