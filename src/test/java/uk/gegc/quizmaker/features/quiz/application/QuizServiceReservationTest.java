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
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
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

/**
 * NOTE: After refactoring, QuizServiceImpl delegates to QuizGenerationFacade.
 * The actual implementation logic is tested in QuizGenerationFacadeImplComplexFlowsTest.
 * These tests verify the delegation works correctly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Quiz Service Reservation Tests")
@Execution(ExecutionMode.CONCURRENT)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
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

    private User testUser;
    private GenerateQuizFromDocumentRequest testRequest;
    private EstimationDto testEstimation;
    private ReservationDto testReservation;
    private QuizGenerationJob testJob;

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
        
        // Configure facade delegation - tests will override as needed
        lenient().when(quizGenerationFacade.startQuizGeneration(anyString(), any()))
                .thenReturn(QuizGenerationResponse.started(testJob.getId(), 300L));
    }

    @Test
    @DisplayName("startQuizGeneration should reserve tokens and create job successfully")
    void startQuizGeneration_ShouldReserveTokensAndCreateJob() throws Exception {
        // Given
        QuizGenerationResponse expectedResponse = QuizGenerationResponse.started(testJob.getId(), 300L);
        when(quizGenerationFacade.startQuizGeneration("testuser", testRequest))
                .thenReturn(expectedResponse);

        // When
        QuizGenerationResponse response = quizService.startQuizGeneration("testuser", testRequest);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.jobId()).isEqualTo(testJob.getId());
        assertThat(response.estimatedTimeSeconds()).isEqualTo(300L);
        
        // Verify delegation to facade
        verify(quizGenerationFacade).startQuizGeneration("testuser", testRequest);
    }

    @Test
    @DisplayName("startQuizGeneration should include zero-balance details when user has no available tokens")
    void startQuizGeneration_ShouldIncludeZeroBalanceDetails() throws Exception {
        // Given - Configure facade to throw InsufficientTokensException
        InsufficientTokensException zeroBalance = new InsufficientTokensException(
                "Not enough tokens to reserve: required=2, available=0",
                2L, 0L, 2L,
                LocalDateTime.now().plusMinutes(30)
        );
        when(quizGenerationFacade.startQuizGeneration("testuser", testRequest))
                .thenThrow(zeroBalance);

        // When & Then
        InsufficientTokensException exception = assertThrows(
                InsufficientTokensException.class,
                () -> quizService.startQuizGeneration("testuser", testRequest)
        );

        assertThat(exception.getEstimatedTokens()).isEqualTo(2L);
        assertThat(exception.getAvailableTokens()).isZero();
        assertThat(exception.getShortfall()).isEqualTo(2L);
        
        // Verify delegation to facade
        verify(quizGenerationFacade).startQuizGeneration("testuser", testRequest);
    }

    @Test
    @DisplayName("startQuizGeneration should throw InsufficientTokensException with details when insufficient balance")
    void startQuizGeneration_ShouldThrowInsufficientTokensException() throws Exception {
        // Given - Configure facade to throw InsufficientTokensException
        InsufficientTokensException billingException = new InsufficientTokensException(
                "Not enough tokens to reserve: required=2, available=1",
                2L, 1L, 1L,
                LocalDateTime.now().plusMinutes(30)
        );
        when(quizGenerationFacade.startQuizGeneration("testuser", testRequest))
                .thenThrow(billingException);

        // When & Then
        InsufficientTokensException exception = assertThrows(
                InsufficientTokensException.class,
                () -> quizService.startQuizGeneration("testuser", testRequest)
        );

        assertThat(exception.getEstimatedTokens()).isEqualTo(2L);
        assertThat(exception.getAvailableTokens()).isEqualTo(1L);
        assertThat(exception.getShortfall()).isEqualTo(1L);
        
        // Verify delegation to facade
        verify(quizGenerationFacade).startQuizGeneration("testuser", testRequest);
    }

    @Test
    @DisplayName("startQuizGeneration should handle idempotency correctly for duplicate requests")
    void startQuizGeneration_ShouldHandleIdempotencyCorrectly() throws Exception {
        // Given - Configure facade to return same response for both calls
        QuizGenerationResponse expectedResponse = QuizGenerationResponse.started(testJob.getId(), 300L);
        when(quizGenerationFacade.startQuizGeneration("testuser", testRequest))
                .thenReturn(expectedResponse);

        // When - make the same request twice
        QuizGenerationResponse response1 = quizService.startQuizGeneration("testuser", testRequest);
        QuizGenerationResponse response2 = quizService.startQuizGeneration("testuser", testRequest);

        // Then
        assertThat(response1).isNotNull();
        assertThat(response2).isNotNull();
        assertThat(response1.jobId()).isEqualTo(response2.jobId());
        assertThat(response1.estimatedTimeSeconds()).isEqualTo(response2.estimatedTimeSeconds());

        // Verify delegation to facade twice
        verify(quizGenerationFacade, times(2)).startQuizGeneration("testuser", testRequest);
    }

    @Test
    @DisplayName("startQuizGeneration should isolate reservations across different users")
    void startQuizGeneration_ShouldIsolateReservationsAcrossDifferentUsers() throws Exception {
        // Given
        GenerateQuizFromDocumentRequest secondRequest = new GenerateQuizFromDocumentRequest(
                UUID.fromString("650e8400-e29b-41d4-a716-446655440005"),
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Second Quiz",
                "Second Quiz Description",
                Map.of(QuestionType.MCQ_SINGLE, 4, QuestionType.TRUE_FALSE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        UUID jobId1 = UUID.fromString("850e8400-e29b-41d4-a716-446655440008");
        UUID jobId2 = UUID.fromString("850e8400-e29b-41d4-a716-446655440009");
        
        when(quizGenerationFacade.startQuizGeneration("testuser", testRequest))
                .thenReturn(QuizGenerationResponse.started(jobId1, 300L));
        when(quizGenerationFacade.startQuizGeneration("user-two", secondRequest))
                .thenReturn(QuizGenerationResponse.started(jobId2, 300L));

        // When
        QuizGenerationResponse responseUser1 = quizService.startQuizGeneration("testuser", testRequest);
        QuizGenerationResponse responseUser2 = quizService.startQuizGeneration("user-two", secondRequest);

        // Then
        assertThat(responseUser1.jobId()).isEqualTo(jobId1);
        assertThat(responseUser2.jobId()).isEqualTo(jobId2);
        
        // Verify delegation to facade for both users
        verify(quizGenerationFacade).startQuizGeneration("testuser", testRequest);
        verify(quizGenerationFacade).startQuizGeneration("user-two", secondRequest);
    }


    @Test
    @DisplayName("startQuizGeneration should throw ValidationException when user has active job")
    void startQuizGeneration_ShouldThrowValidationExceptionWhenActiveJobExists() {
        // Given - Configure facade to throw ValidationException
        when(quizGenerationFacade.startQuizGeneration("testuser", testRequest))
                .thenThrow(new uk.gegc.quizmaker.shared.exception.ValidationException("User already has an active generation job"));

        // When & Then
        uk.gegc.quizmaker.shared.exception.ValidationException exception = assertThrows(
                uk.gegc.quizmaker.shared.exception.ValidationException.class,
                () -> quizService.startQuizGeneration("testuser", testRequest)
        );

        assertThat(exception.getMessage()).contains("User already has an active generation job");
        
        // Verify delegation to facade
        verify(quizGenerationFacade).startQuizGeneration("testuser", testRequest);
    }

    @Test
    @DisplayName("startQuizGeneration should throw ResourceNotFoundException when user not found")
    void startQuizGeneration_ShouldThrowResourceNotFoundExceptionWhenUserNotFound() {
        // Given - Configure facade to throw ResourceNotFoundException
        when(quizGenerationFacade.startQuizGeneration("nonexistent", testRequest))
                .thenThrow(new uk.gegc.quizmaker.shared.exception.ResourceNotFoundException("User not found: nonexistent"));

        // When & Then
        uk.gegc.quizmaker.shared.exception.ResourceNotFoundException exception = assertThrows(
                uk.gegc.quizmaker.shared.exception.ResourceNotFoundException.class,
                () -> quizService.startQuizGeneration("nonexistent", testRequest)
        );

        assertThat(exception.getMessage()).contains("User not found");
        
        // Verify delegation to facade
        verify(quizGenerationFacade).startQuizGeneration("nonexistent", testRequest);
    }
}
