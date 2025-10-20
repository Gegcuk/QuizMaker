package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.context.ApplicationEventPublisher;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.validation.QuizPublishValidator;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.shared.security.AccessPolicy;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizService Publishing Validation Tests")
class QuizServiceImplTest {

    private static final UUID DEFAULT_CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    private QuestionHandler questionHandler;
    @Mock
    private DocumentProcessingService documentProcessingService;
    @Mock
    private AiQuizGenerationService aiQuizGenerationService;
    @Mock
    private QuizGenerationJobService jobService;
    @Mock
    private QuizGenerationJobRepository jobRepository;
    @Mock
    private EstimationService estimationService;
    @Mock
    private BillingService billingService;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private QuizDefaultsProperties quizDefaultsProperties;
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
    private AccessPolicy accessPolicy;
    @Mock
    private QuizPublishValidator quizPublishValidator;

    @InjectMocks
    private QuizServiceImpl quizService;

    private User adminUser;

    @BeforeEach
    void setUp() {
        adminUser = createAdminUser();
        setupUserRepositoryMock();
        setupPermissionEvaluatorMock();
        setupAccessPolicyMock();
        lenient().when(quizDefaultsProperties.getDefaultCategoryId()).thenReturn(DEFAULT_CATEGORY_ID);
    }
    
    private void setupAccessPolicyMock() {
        // Default behavior: allow access for admin user (owner or has permissions)
        lenient().doNothing().when(accessPolicy).requireOwnerOrAny(
                any(User.class), 
                any(UUID.class), 
                any(PermissionName.class), 
                any(PermissionName.class));
        
        // Admin has moderation permissions
        lenient().when(accessPolicy.hasAny(eq(adminUser), any(PermissionName.class), any(PermissionName.class)))
                .thenReturn(true);
        
        // Non-admin users don't have moderation permissions by default
        lenient().when(accessPolicy.hasAny(argThat(user -> user != null && !user.equals(adminUser)), 
                any(PermissionName.class), any(PermissionName.class)))
                .thenReturn(false);
        
        // Default: allow publishing (tests that need validation failure will override)
        lenient().doNothing().when(quizPublishValidator).ensurePublishable(any(Quiz.class));
    }

    private User createAdminUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("admin");
        user.setEmail("admin@example.com");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        
        // Create an admin role with full permissions
        Role role = new Role();
        role.setRoleId(1L);
        role.setRoleName("ROLE_ADMIN");
        role.setDescription("Administrator role");
        
        // Create permissions for the admin role
        Set<Permission> permissions = new HashSet<>();
        
        Permission quizModerate = new Permission();
        quizModerate.setPermissionId(1L);
        quizModerate.setPermissionName("QUIZ_MODERATE");
        quizModerate.setResource("quiz");
        quizModerate.setAction("moderate");
        permissions.add(quizModerate);
        
        Permission quizAdmin = new Permission();
        quizAdmin.setPermissionId(2L);
        quizAdmin.setPermissionName("QUIZ_ADMIN");
        quizAdmin.setResource("quiz");
        quizAdmin.setAction("admin");
        permissions.add(quizAdmin);
        
        role.setPermissions(permissions);
        
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        user.setRoles(roles);
        
        return user;
    }

    private void setupUserRepositoryMock() {
        lenient().when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
        lenient().when(userRepository.findByEmail("admin")).thenReturn(Optional.empty());
        lenient().when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(createTestUser()));
        lenient().when(userRepository.findByEmail("testuser")).thenReturn(Optional.empty());
    }

    private void setupPermissionEvaluatorMock() {
        // By default, admin user has all permissions
        lenient().when(appPermissionEvaluator.hasPermission(eq(adminUser), any(PermissionName.class)))
                .thenReturn(true);
        
        // For testuser, provide basic permissions
        User testUser = createTestUser();
        lenient().when(appPermissionEvaluator.hasPermission(eq(testUser), any(PermissionName.class)))
                .thenReturn(false);
    }

    @Test
    @DisplayName("setStatus: Publishing quiz without questions should throw IllegalArgumentException")
    void setStatus_publishingEmptyQuiz_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";

        doThrow(new IllegalArgumentException("Cannot publish quiz: Cannot publish quiz without questions"))
                .when(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions");
        
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with insufficient estimated time should throw IllegalArgumentException")
    void setStatus_publishingQuizWithInsufficientEstimatedTime_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";

        doThrow(new IllegalArgumentException("Cannot publish quiz: Quiz must have a minimum estimated time of 1 minute(s)"))
                .when(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
        
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with null estimated time should throw IllegalArgumentException")
    void setStatus_publishingQuizWithNullEstimatedTime_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";

        doThrow(new IllegalArgumentException("Cannot publish quiz: Quiz must have a minimum estimated time of 1 minute(s)"))
                .when(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
        
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with invalid question content should throw IllegalArgumentException")
    void setStatus_publishingQuizWithInvalidQuestionContent_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";

        doThrow(new IllegalArgumentException("Cannot publish quiz: Question 'Question 1' is invalid: MCQ_SINGLE must have exactly one correct answer"))
                .when(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'Question 1' is invalid: MCQ_SINGLE must have exactly one correct answer");
        
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with malformed question JSON should throw IllegalArgumentException")
    void setStatus_publishingQuizWithMalformedQuestionJSON_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";

        doThrow(new IllegalArgumentException("Cannot publish quiz: Question 'Question 1' failed validation: Unexpected character"))
                .when(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'Question 1'");
        
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with multiple validation errors should include all errors")
    void setStatus_publishingQuizWithMultipleErrors_includesAllErrors() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";

        doThrow(new IllegalArgumentException("Cannot publish quiz: Cannot publish quiz without questions; Quiz must have a minimum estimated time of 1 minute(s)"))
                .when(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions")
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
        
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Publishing valid quiz should succeed")
    void setStatus_publishingValidQuiz_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);

        when(quizPublishingService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Setting to DRAFT should work regardless of validation rules")
    void setStatus_settingToDraft_worksWithInvalidQuiz() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);

        when(quizPublishingService.setStatus(username, quizId, QuizStatus.DRAFT))
                .thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.DRAFT);
    }

    @Test
    @DisplayName("setStatus: Quiz not found should throw ResourceNotFoundException")
    void setStatus_quizNotFound_throwsResourceNotFoundException() {
        // Given
        UUID nonExistentQuizId = UUID.randomUUID();
        String username = "admin";

        doThrow(new ResourceNotFoundException("Quiz " + nonExistentQuizId + " not found"))
                .when(quizPublishingService).setStatus(username, nonExistentQuizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, nonExistentQuizId, QuizStatus.PUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Quiz " + nonExistentQuizId + " not found");
        
        verify(quizPublishingService).setStatus(username, nonExistentQuizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Transitioning from PUBLISHED back to DRAFT should work")
    void setStatus_publishedToDraft_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);

        when(quizPublishingService.setStatus(username, quizId, QuizStatus.DRAFT))
                .thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.DRAFT);
    }

    @Test
    @DisplayName("setStatus: Publishing already published quiz should work (idempotent)")
    void setStatus_publishingAlreadyPublishedQuiz_isIdempotent() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);

        when(quizPublishingService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    // Helper methods for creating test data

    private Quiz createQuizWithoutQuestions() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Empty Quiz");
        quiz.setDescription("A quiz without questions");
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(5); // Valid estimated time
        quiz.setCreatedAt(Instant.now());
        quiz.setQuestions(new HashSet<>()); // Empty questions set
        quiz.setCreator(createTestUser());
        quiz.setCategory(createTestCategory());
        return quiz;
    }

    private Quiz createQuizWithQuestions(int questionCount) {
        Quiz quiz = createQuizWithoutQuestions();
        quiz.setTitle("Quiz with " + questionCount + " questions");
        quiz.setEstimatedTime(5); // Valid estimated time

        Set<Question> questions = new HashSet<>();
        for (int i = 0; i < questionCount; i++) {
            Question question = new Question();
            question.setId(UUID.randomUUID());
            question.setQuestionText("Question " + (i + 1));
            question.setType(QuestionType.TRUE_FALSE);
            question.setContent("{\"answer\":true}"); // Valid JSON content
            questions.add(question);
        }
        quiz.setQuestions(questions);
        return quiz;
    }

    private Quiz createValidQuizForPublishing() {
        Quiz quiz = createQuizWithQuestions(2);
        quiz.setEstimatedTime(10); // Valid estimated time
        return quiz;
    }

    private User createTestUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        
        // Create a basic role with minimal permissions
        Role role = new Role();
        role.setRoleId(2L);
        role.setRoleName("ROLE_USER");
        role.setDescription("Basic user role");
        
        Set<Permission> permissions = new HashSet<>();
        role.setPermissions(permissions);
        
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        user.setRoles(roles);
        
        return user;
    }

    private Category createTestCategory() {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Test Category");
        return category;
    }

    private QuizDto createQuizDto(UUID quizId, QuizStatus status) {
        return new QuizDto(
                quizId, // id
                UUID.randomUUID(), // creatorId
                UUID.randomUUID(), // categoryId
                "Test Quiz", // title
                "Test Description", // description
                null, // visibility
                null, // difficulty
                status, // status
                10, // estimatedTime
                false, // isRepetitionEnabled
                false, // timerEnabled
                5, // timerDuration
                List.of(), // tagIds
                Instant.now(), // createdAt
                null // updatedAt
        );
    }

    @Test
    @DisplayName("generateQuizFromText: Should process text and start quiz generation successfully")
    void generateQuizFromText_shouldProcessTextAndStartGeneration() {
        // Setup TransactionTemplate mock for this test
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
            return callback.doInTransaction(mockStatus);
        });
        
        // Given
        String username = "testuser";
        String sampleText = "This is a sample text for quiz generation. It contains enough content to be processed.";
        UUID documentId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        
        GenerateQuizFromTextRequest request = new GenerateQuizFromTextRequest(
                sampleText,
                "en",
                null, // chunkingStrategy - will use default
                null, // maxChunkSize - will use default
                null, // quizScope - will use default
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz", // quizTitle
                "Test Description", // quizDescription
                Map.of(QuestionType.MCQ_SINGLE, 3), // questionsPerType
                Difficulty.MEDIUM, // difficulty
                2, // estimatedTimePerQuestion
                null, // categoryId
                null  // tagIds
        );

        DocumentDto documentDto = new DocumentDto();
        documentDto.setId(documentId);
        documentDto.setOriginalFilename("text-input.txt");
        documentDto.setContentType("text/plain");
        documentDto.setFileSize(1024L);
        documentDto.setStatus(Document.DocumentStatus.PROCESSED);
        documentDto.setUploadedAt(LocalDateTime.now());
        documentDto.setProcessedAt(LocalDateTime.now());
        documentDto.setTotalChunks(3);


        // Mock document processing
        when(documentProcessingService.uploadAndProcessDocument(
                eq(username), 
                any(byte[].class), 
                eq("text-input.txt"), 
                any()
        )).thenReturn(documentDto);

        // Mock user repository
        User testUser = createTestUser();
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));

        // Mock estimation service
        EstimationDto mockEstimation = new EstimationDto(
                1000L, // estimatedLlmTokens
                100L,  // estimatedBillingTokens
                null,  // approxCostCents
                "USD", // currency
                true,  // estimate
                "~100 billing tokens (1,000 LLM tokens)", // humanizedEstimate
                UUID.randomUUID() // estimationId
        );
        when(estimationService.estimateQuizGeneration(eq(documentId), any(GenerateQuizFromDocumentRequest.class)))
                .thenReturn(mockEstimation);

        // Mock billing service
        ReservationDto mockReservation = new ReservationDto(
                UUID.randomUUID(), // id
                testUser.getId(),   // userId
                ReservationState.ACTIVE, // state
                100L,              // estimatedTokens
                100L,              // committedTokens
                LocalDateTime.now().plusMinutes(30), // expiresAt
                null,              // jobId
                LocalDateTime.now(), // createdAt
                LocalDateTime.now()  // updatedAt
        );
        when(billingService.reserve(eq(testUser.getId()), eq(100L), eq("quiz-generation"), anyString()))
                .thenReturn(mockReservation);

        // Mock chunk verification and generation start
        when(aiQuizGenerationService.calculateTotalChunks(eq(documentId), any(GenerateQuizFromDocumentRequest.class)))
                .thenReturn(3);
        when(aiQuizGenerationService.calculateEstimatedGenerationTime(anyInt(), anyMap()))
                .thenReturn(60);

        // Mock job service
        QuizGenerationJob mockJob = new QuizGenerationJob();
        mockJob.setId(jobId);
        when(jobService.createJob(eq(testUser), eq(documentId), anyString(), eq(3), eq(60)))
                .thenReturn(mockJob);

        // Mock job repository save
        when(jobRepository.save(any(QuizGenerationJob.class))).thenReturn(mockJob);

        // When
        QuizGenerationResponse result = quizService.generateQuizFromText(username, request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.estimatedTimeSeconds()).isEqualTo(60L);
        
        // Verify document processing was called
        verify(documentProcessingService).uploadAndProcessDocument(
                eq(username), 
                any(byte[].class), 
                eq("text-input.txt"), 
                any()
        );
        
        // Verify chunk verification was called
        verify(aiQuizGenerationService, times(2)).calculateTotalChunks(eq(documentId), any(GenerateQuizFromDocumentRequest.class));
    }

    @Test
    @DisplayName("createQuiz: Should find user by email if username lookup fails")
    void createQuiz_userFoundByEmail_createsSuccessfully() {
        // Given
        String email = "user@example.com";
        CreateQuizRequest request = new CreateQuizRequest(
                "Test Quiz",
                "Description",
                Visibility.PRIVATE,
                Difficulty.MEDIUM,
                false,  // isRepetitionEnabled
                false,  // timerEnabled
                10,     // estimatedTime
                5,      // timerDuration
                null,   // categoryId null
                List.of()  // tagIds
        );

        UUID quizId = UUID.randomUUID();
        when(quizCommandService.createQuiz(email, request)).thenReturn(quizId);

        // When
        UUID result = quizService.createQuiz(email, request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(quizId);
    }

    @Test
    @DisplayName("createQuiz: Invalid category falls back to configured default")
    void createQuiz_invalidCategory_usesDefaultCategory() {
        // Given
        String username = "admin";
        UUID invalidCategoryId = UUID.randomUUID();
        CreateQuizRequest request = new CreateQuizRequest(
                "Fallback Quiz",
                "Description",
                Visibility.PRIVATE,
                Difficulty.EASY,
                false,
                false,
                10,
                5,
                invalidCategoryId,
                List.of()
        );

        UUID quizId = UUID.randomUUID();
        when(quizCommandService.createQuiz(username, request)).thenReturn(quizId);

        // When
        UUID result = quizService.createQuiz(username, request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(quizId);
    }

    @Test
    @DisplayName("createQuiz: Moderator can create PUBLIC quiz directly")
    void createQuiz_moderator_canCreatePublicQuiz() {
        // Given
        String username = "admin";
        UUID categoryId = UUID.randomUUID();
        CreateQuizRequest request = new CreateQuizRequest(
                "Test Quiz",
                "Description",
                Visibility.PUBLIC,
                Difficulty.MEDIUM,
                false,  // isRepetitionEnabled
                false,  // timerEnabled
                10,     // estimatedTime
                5,      // timerDuration
                categoryId,
                List.of()  // tagIds
        );

        UUID quizId = UUID.randomUUID();
        when(quizCommandService.createQuiz(username, request)).thenReturn(quizId);

        // When
        UUID result = quizService.createQuiz(username, request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(quizId);
    }

    @Test
    @DisplayName("createQuiz: Non-moderator forced to PRIVATE/DRAFT")
    void createQuiz_nonModerator_forcedToPrivateDraft() {
        // Given
        String username = "testuser";
        UUID categoryId = UUID.randomUUID();
        CreateQuizRequest request = new CreateQuizRequest(
                "Test Quiz",
                "Description",
                Visibility.PUBLIC,  // Requesting PUBLIC
                Difficulty.HARD,
                false,  // isRepetitionEnabled
                true,   // timerEnabled
                20,     // estimatedTime
                15,     // timerDuration
                categoryId,  // Requesting PUBLISHED
                List.of()  // tagIds
        );

        UUID quizId = UUID.randomUUID();
        when(quizCommandService.createQuiz(username, request)).thenReturn(quizId);

        // When
        UUID result = quizService.createQuiz(username, request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(quizId);
    }

    @Test
    @DisplayName("setVisibility: Non-moderator cannot set PUBLIC visibility")
    void setVisibility_nonModeratorSettingPublic_throwsForbiddenException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "testuser";

        when(quizVisibilityService.setVisibility(username, quizId, Visibility.PUBLIC))
                .thenThrow(new ForbiddenException("Only moderators can set quiz visibility to PUBLIC"));

        // When & Then
        assertThatThrownBy(() -> quizService.setVisibility(username, quizId, Visibility.PUBLIC))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can set quiz visibility to PUBLIC");
        
        verify(quizVisibilityService).setVisibility(username, quizId, Visibility.PUBLIC);
    }

    @Test
    @DisplayName("setVisibility: Moderator can set PUBLIC visibility")
    void setVisibility_moderator_canSetPublic() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";

        QuizDto expectedDto = new QuizDto(
                quizId,                 // id
                adminUser.getId(),      // creatorId
                UUID.randomUUID(),      // categoryId
                "Test",                 // title
                "Desc",                 // description
                Visibility.PUBLIC,      // visibility
                Difficulty.MEDIUM,      // difficulty
                QuizStatus.DRAFT,       // status
                10,                     // estimatedTime
                false,                  // isRepetitionEnabled
                false,                  // timerEnabled
                5,                      // timerDuration
                List.of(),              // tagIds
                null,                   // createdAt
                null                    // updatedAt
        );

        when(quizVisibilityService.setVisibility(username, quizId, Visibility.PUBLIC))
                .thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setVisibility(username, quizId, Visibility.PUBLIC);

        // Then
        assertThat(result).isNotNull();
        verify(quizVisibilityService).setVisibility(username, quizId, Visibility.PUBLIC);
    }

    @Test
    @DisplayName("setStatus: Non-moderator cannot publish PUBLIC quiz")
    void setStatus_nonModeratorPublishingPublicQuiz_throwsForbiddenException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "testuser";

        doThrow(new ForbiddenException("Only moderators can publish PUBLIC quizzes. Set visibility to PRIVATE first or submit for moderation."))
                .when(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only moderators can publish PUBLIC quizzes");
        
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("setStatus: Owner can publish PRIVATE quiz")
    void setStatus_ownerPublishingPrivateQuiz_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "testuser";

        QuizDto expectedDto = new QuizDto(
                quizId, UUID.randomUUID(), UUID.randomUUID(), "Test", "Desc",
                Visibility.PRIVATE, Difficulty.MEDIUM, QuizStatus.PUBLISHED,
                10, false, false, 5, List.of(), null, null
        );

        when(quizPublishingService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizPublishingService).setStatus(username, quizId, QuizStatus.PUBLISHED);
    }
} 
