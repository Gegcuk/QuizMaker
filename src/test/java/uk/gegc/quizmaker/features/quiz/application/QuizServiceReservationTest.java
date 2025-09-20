package uk.gegc.quizmaker.features.quiz.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationRequestedEvent;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quiz Service Reservation Tests")
@Execution(ExecutionMode.CONCURRENT)
class QuizServiceReservationTest {

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
    private EstimationService estimationService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private QuizServiceImpl quizService;

    private User testUser;
    private GenerateQuizFromDocumentRequest testRequest;
    private EstimationDto testEstimation;
    private ReservationDto testReservation;
    private QuizGenerationJob testJob;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");

        testRequest = new GenerateQuizFromDocumentRequest(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"), // documentId
                QuizScope.ENTIRE_DOCUMENT, // quizScope
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz", // quizTitle
                "Test Quiz Description", // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 5, QuestionType.TRUE_FALSE, 3), // questionsPerType
                Difficulty.MEDIUM, // difficulty
                2, // estimatedTimePerQuestion
                null, // categoryId
                List.of() // tagIds
        );

        testEstimation = new EstimationDto(
                1500L, // estimatedLlmTokens
                2L,    // estimatedBillingTokens
                null,  // approxCostCents
                "usd", // currency
                true,  // estimate
                "~2 billing tokens (1,500 LLM tokens)", // humanizedEstimate
                UUID.randomUUID() // estimationId
        );

        testReservation = new ReservationDto(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440002"), // id
                testUser.getId(), // userId
                uk.gegc.quizmaker.features.billing.domain.model.ReservationState.ACTIVE, // state
                2L, // estimatedTokens
                0L, // committedTokens
                LocalDateTime.now().plusMinutes(30), // expiresAt
                null, // jobId
                LocalDateTime.now(), // createdAt
                LocalDateTime.now() // updatedAt
        );

        testJob = new QuizGenerationJob();
        testJob.setId(UUID.fromString("550e8400-e29b-41d4-a716-446655440003"));
        testJob.setUser(testUser);
        testJob.setDocumentId(testRequest.documentId());
        testJob.setStatus(GenerationStatus.PENDING);
        testJob.setTotalChunks(10);
        testJob.setEstimatedCompletion(LocalDateTime.now().plusMinutes(5));
    }

    @Test
    @DisplayName("startQuizGeneration should reserve tokens and create job successfully")
    void startQuizGeneration_ShouldReserveTokensAndCreateJob() throws Exception {
        // Setup TransactionTemplate mock for this test
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
            return callback.doInTransaction(mockStatus);
        });
        
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(estimationService.estimateQuizGeneration(any(), any())).thenReturn(testEstimation);
        when(aiQuizGenerationService.calculateTotalChunks(any(), any())).thenReturn(10);
        when(aiQuizGenerationService.calculateEstimatedGenerationTime(anyInt(), any())).thenReturn(300);
        when(jobService.createJob(any(), any(), any(), anyInt(), anyInt())).thenReturn(testJob);
        when(billingService.reserve(any(), anyLong(), any(), any())).thenReturn(testReservation);
        when(jobRepository.save(any())).thenReturn(testJob);

        // When
        QuizGenerationResponse response = quizService.startQuizGeneration("testuser", testRequest);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.jobId()).isEqualTo(testJob.getId());
        assertThat(response.estimatedTimeSeconds()).isEqualTo(300L);

        // Verify estimation was called
        verify(estimationService).estimateQuizGeneration(testRequest.documentId(), testRequest);

        // Verify reservation was called with correct parameters
        // The idempotency key format is now: "quiz:userId:documentId:scope"
        verify(billingService).reserve(
                eq(testUser.getId()),
                eq(2L), // estimatedBillingTokens
                eq("quiz-generation"),
                eq("quiz:" + testUser.getId() + ":" + testRequest.documentId() + ":ENTIRE_DOCUMENT")
        );

        // Verify job was updated with reservation details
        verify(jobRepository).save(argThat(job -> {
            QuizGenerationJob savedJob = (QuizGenerationJob) job;
            return savedJob.getBillingReservationId().equals(testReservation.id()) &&
                   savedJob.getBillingEstimatedTokens().equals(2L) &&
                   savedJob.getBillingState() == BillingState.RESERVED &&
                   savedJob.getReservationExpiresAt() != null;
        }));

        // Verify event was published to start async generation
        verify(applicationEventPublisher).publishEvent(any(QuizGenerationRequestedEvent.class));
    }

    @Test
    @DisplayName("startQuizGeneration should include zero-balance details when user has no available tokens")
    void startQuizGeneration_ShouldIncludeZeroBalanceDetails() throws Exception {
        // Setup TransactionTemplate mock for this test
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
            return callback.doInTransaction(mockStatus);
        });
        
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(estimationService.estimateQuizGeneration(any(), any())).thenReturn(testEstimation);

        InsufficientTokensException zeroBalance = new InsufficientTokensException(
                "Not enough tokens to reserve: required=2, available=0",
                2L,
                0L,
                2L,
                LocalDateTime.now().plusMinutes(30)
        );
        when(billingService.reserve(any(), anyLong(), any(), any())).thenThrow(zeroBalance);

        InsufficientTokensException exception = assertThrows(
                InsufficientTokensException.class,
                () -> quizService.startQuizGeneration("testuser", testRequest)
        );

        assertThat(exception.getEstimatedTokens()).isEqualTo(2L);
        assertThat(exception.getAvailableTokens()).isZero();
        assertThat(exception.getShortfall()).isEqualTo(2L);
        assertThat(exception.getReservationTtl()).isNotNull();

        verify(billingService).reserve(any(), anyLong(), any(), any());
        verify(jobService, never()).createJob(any(), any(), any(), anyInt(), anyInt());
        verify(aiQuizGenerationService, never()).generateQuizFromDocumentAsync(any(UUID.class), any());
    }

    @Test
    @DisplayName("startQuizGeneration should throw InsufficientTokensException with details when insufficient balance")
    void startQuizGeneration_ShouldThrowInsufficientTokensException() throws Exception {
        // Setup TransactionTemplate mock for this test
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
            return callback.doInTransaction(mockStatus);
        });
        
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(estimationService.estimateQuizGeneration(any(), any())).thenReturn(testEstimation);
        
        InsufficientTokensException billingException = new InsufficientTokensException(
                "Not enough tokens to reserve: required=2, available=1",
                2L, // estimatedTokens
                1L, // availableTokens
                1L, // shortfall
                LocalDateTime.now().plusMinutes(30) // reservationTtl
        );
        when(billingService.reserve(any(), anyLong(), any(), any())).thenThrow(billingException);

        // When & Then
        InsufficientTokensException exception = assertThrows(
                InsufficientTokensException.class,
                () -> quizService.startQuizGeneration("testuser", testRequest)
        );

        assertThat(exception.getEstimatedTokens()).isEqualTo(2L);
        assertThat(exception.getAvailableTokens()).isEqualTo(1L);
        assertThat(exception.getShortfall()).isEqualTo(1L);
        assertThat(exception.getReservationTtl()).isNotNull();

        // Verify job was NOT created because reservation failed first
        verify(jobService, never()).createJob(any(), any(), any(), anyInt(), anyInt());
        verify(jobRepository, never()).delete(any());

        // Verify async generation was NOT started
        verify(aiQuizGenerationService, never()).generateQuizFromDocumentAsync(any(UUID.class), any());
    }

    @Test
    @DisplayName("startQuizGeneration should handle idempotency correctly for duplicate requests")
    void startQuizGeneration_ShouldHandleIdempotencyCorrectly() throws Exception {
        // Setup TransactionTemplate mock for this test
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
            return callback.doInTransaction(mockStatus);
        });
        
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(estimationService.estimateQuizGeneration(any(), any())).thenReturn(testEstimation);
        when(aiQuizGenerationService.calculateTotalChunks(any(), any())).thenReturn(10);
        when(aiQuizGenerationService.calculateEstimatedGenerationTime(anyInt(), any())).thenReturn(300);
        when(jobService.createJob(any(), any(), any(), anyInt(), anyInt())).thenReturn(testJob);
        
        // First call succeeds
        when(billingService.reserve(any(), anyLong(), any(), any())).thenReturn(testReservation);
        when(jobRepository.save(any())).thenReturn(testJob);

        // When - make the same request twice
        QuizGenerationResponse response1 = quizService.startQuizGeneration("testuser", testRequest);
        QuizGenerationResponse response2 = quizService.startQuizGeneration("testuser", testRequest);

        // Then
        assertThat(response1).isNotNull();
        assertThat(response2).isNotNull();
        assertThat(response1.jobId()).isEqualTo(response2.jobId());
        assertThat(response1.estimatedTimeSeconds()).isEqualTo(response2.estimatedTimeSeconds());

        // Verify reservation was called twice with the same stable idempotency key
        verify(billingService, times(2)).reserve(
                eq(testUser.getId()),
                eq(2L),
                eq("quiz-generation"),
                eq("quiz:" + testUser.getId() + ":" + testRequest.documentId() + ":ENTIRE_DOCUMENT")
        );

        // The billing service should handle idempotency internally
        // and return the same reservation for duplicate requests
    }

    @Test
    @DisplayName("startQuizGeneration should isolate reservations across different users")
    void startQuizGeneration_ShouldIsolateReservationsAcrossDifferentUsers() throws Exception {
        // Setup TransactionTemplate mock for this test
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
            return callback.doInTransaction(mockStatus);
        });
        
        User secondUser = new User();
        secondUser.setId(UUID.fromString("650e8400-e29b-41d4-a716-446655440004"));
        secondUser.setUsername("user-two");
        secondUser.setEmail("user-two@example.com");

        GenerateQuizFromDocumentRequest secondRequest = new GenerateQuizFromDocumentRequest(
                UUID.fromString("650e8400-e29b-41d4-a716-446655440005"),
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Second Quiz",
                "Second Quiz Description",
                Map.of(QuestionType.MCQ_SINGLE, 4, QuestionType.TRUE_FALSE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        ReservationDto reservationUser1 = new ReservationDto(
                UUID.fromString("750e8400-e29b-41d4-a716-446655440006"),
                testUser.getId(),
                uk.gegc.quizmaker.features.billing.domain.model.ReservationState.ACTIVE,
                2L,
                0L,
                LocalDateTime.now().plusMinutes(30),
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        ReservationDto reservationUser2 = new ReservationDto(
                UUID.fromString("750e8400-e29b-41d4-a716-446655440007"),
                secondUser.getId(),
                uk.gegc.quizmaker.features.billing.domain.model.ReservationState.ACTIVE,
                2L,
                0L,
                LocalDateTime.now().plusMinutes(30),
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        QuizGenerationJob jobUser1 = new QuizGenerationJob();
        jobUser1.setId(UUID.fromString("850e8400-e29b-41d4-a716-446655440008"));
        jobUser1.setUser(testUser);
        jobUser1.setStatus(GenerationStatus.PENDING);

        QuizGenerationJob jobUser2 = new QuizGenerationJob();
        jobUser2.setId(UUID.fromString("850e8400-e29b-41d4-a716-446655440009"));
        jobUser2.setUser(secondUser);
        jobUser2.setStatus(GenerationStatus.PENDING);

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(userRepository.findByUsername("user-two")).thenReturn(Optional.of(secondUser));
        when(estimationService.estimateQuizGeneration(any(), any())).thenReturn(testEstimation);
        when(aiQuizGenerationService.calculateTotalChunks(any(), any())).thenReturn(10);
        when(aiQuizGenerationService.calculateEstimatedGenerationTime(anyInt(), any())).thenReturn(300);
        when(jobService.createJob(any(), any(), any(), anyInt(), anyInt())).thenReturn(jobUser1, jobUser2);
        when(billingService.reserve(any(), anyLong(), any(), any())).thenReturn(reservationUser1, reservationUser2);
        when(jobRepository.save(any())).thenAnswer(invocation -> (QuizGenerationJob) invocation.getArgument(0));

        QuizGenerationResponse responseUser1 = quizService.startQuizGeneration("testuser", testRequest);
        QuizGenerationResponse responseUser2 = quizService.startQuizGeneration("user-two", secondRequest);

        assertThat(responseUser1.jobId()).isEqualTo(jobUser1.getId());
        assertThat(responseUser2.jobId()).isEqualTo(jobUser2.getId());
        assertThat(responseUser1.estimatedTimeSeconds()).isEqualTo(300L);
        assertThat(responseUser2.estimatedTimeSeconds()).isEqualTo(300L);

        verify(billingService).reserve(
                eq(testUser.getId()),
                eq(2L),
                eq("quiz-generation"),
                eq("quiz:" + testUser.getId() + ":" + testRequest.documentId() + ":ENTIRE_DOCUMENT")
        );
        verify(billingService).reserve(
                eq(secondUser.getId()),
                eq(2L),
                eq("quiz-generation"),
                eq("quiz:" + secondUser.getId() + ":" + secondRequest.documentId() + ":ENTIRE_DOCUMENT")
        );

        // Verify events were published to start async generation for both users
        verify(applicationEventPublisher, times(2)).publishEvent(any(QuizGenerationRequestedEvent.class));
    }


    @Test
    @DisplayName("startQuizGeneration should throw ValidationException when user has active job")
    void startQuizGeneration_ShouldThrowValidationExceptionWhenActiveJobExists() {
        // Setup TransactionTemplate mock for this test
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
            return callback.doInTransaction(mockStatus);
        });
        
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(estimationService.estimateQuizGeneration(any(), any())).thenReturn(testEstimation);
        when(billingService.reserve(any(), anyLong(), any(), any())).thenReturn(testReservation);
        when(aiQuizGenerationService.calculateTotalChunks(any(), any())).thenReturn(10);
        when(aiQuizGenerationService.calculateEstimatedGenerationTime(anyInt(), any())).thenReturn(300);
        
        // Mock the job creation to fail due to unique constraint violation
        org.springframework.dao.DataIntegrityViolationException dbException = 
                new org.springframework.dao.DataIntegrityViolationException("Unique constraint violation: active_user_id");
        when(jobService.createJob(any(), any(), any(), anyInt(), anyInt())).thenThrow(dbException);

        // When & Then
        uk.gegc.quizmaker.shared.exception.ValidationException exception = assertThrows(
                uk.gegc.quizmaker.shared.exception.ValidationException.class,
                () -> quizService.startQuizGeneration("testuser", testRequest)
        );

        assertThat(exception.getMessage()).contains("User already has an active generation job");

        // Verify estimation and reservation were attempted, but job creation failed
        verify(estimationService).estimateQuizGeneration(any(), any());
        verify(billingService).reserve(any(), anyLong(), any(), any());
        verify(jobService).createJob(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("startQuizGeneration should throw ResourceNotFoundException when user not found")
    void startQuizGeneration_ShouldThrowResourceNotFoundExceptionWhenUserNotFound() {
        // Given
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        // When & Then
        uk.gegc.quizmaker.shared.exception.ResourceNotFoundException exception = assertThrows(
                uk.gegc.quizmaker.shared.exception.ResourceNotFoundException.class,
                () -> quizService.startQuizGeneration("nonexistent", testRequest)
        );

        assertThat(exception.getMessage()).contains("User not found");

        // Verify no estimation or reservation was attempted
        verify(estimationService, never()).estimateQuizGeneration(any(), any());
        verify(billingService, never()).reserve(any(), anyLong(), any(), any());
    }
}
