package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizService Publishing Validation Tests")
class QuizServiceImplTest {

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

    @InjectMocks
    private QuizServiceImpl quizService;

    @Test
    @DisplayName("setStatus: Publishing quiz without questions should throw IllegalArgumentException")
    void setStatus_publishingEmptyQuiz_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz emptyQuiz = createQuizWithoutQuestions();

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(emptyQuiz));

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with insufficient estimated time should throw IllegalArgumentException")
    void setStatus_publishingQuizWithInsufficientEstimatedTime_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);
        quiz.setEstimatedTime(0); // Invalid time

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with null estimated time should throw IllegalArgumentException")
    void setStatus_publishingQuizWithNullEstimatedTime_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);
        quiz.setEstimatedTime(null); // Null time

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with invalid question content should throw IllegalArgumentException")
    void setStatus_publishingQuizWithInvalidQuestionContent_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);
        doThrow(new ValidationException("MCQ_SINGLE must have exactly one correct answer")).when(questionHandler).validateContent(any());

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'Question 1' is invalid: MCQ_SINGLE must have exactly one correct answer");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with malformed question JSON should throw IllegalArgumentException")
    void setStatus_publishingQuizWithMalformedQuestionJSON_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);
        // Set malformed JSON content
        quiz.getQuestions().iterator().next().setContent("invalid json {");

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'Question 1' has malformed content JSON");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with multiple validation errors should include all errors")
    void setStatus_publishingQuizWithMultipleErrors_includesAllErrors() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithoutQuestions();
        quiz.setEstimatedTime(0); // Invalid time

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions")
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
    }

    @Test
    @DisplayName("setStatus: Publishing valid quiz should succeed")
    void setStatus_publishingValidQuiz_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createValidQuizForPublishing();
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(quizRepository.save(quiz)).thenReturn(quiz);
        when(quizMapper.toDto(quiz)).thenReturn(expectedDto);
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);
        // Mock successful validation - no exception thrown

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(quiz);
        verify(questionHandler, times(2)).validateContent(any()); // Called once for each question
    }

    @Test
    @DisplayName("setStatus: Setting to DRAFT should work regardless of validation rules")
    void setStatus_settingToDraft_worksWithInvalidQuiz() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz invalidQuiz = createQuizWithoutQuestions();
        invalidQuiz.setEstimatedTime(0); // Invalid for publishing, but OK for draft
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(invalidQuiz));
        when(quizRepository.save(invalidQuiz)).thenReturn(invalidQuiz);
        when(quizMapper.toDto(invalidQuiz)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(invalidQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        verify(quizRepository).save(invalidQuiz);
    }

    @Test
    @DisplayName("setStatus: Quiz not found should throw ResourceNotFoundException")
    void setStatus_quizNotFound_throwsResourceNotFoundException() {
        // Given
        UUID nonExistentQuizId = UUID.randomUUID();
        String username = "admin";

        when(quizRepository.findByIdWithQuestions(nonExistentQuizId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, nonExistentQuizId, QuizStatus.PUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Quiz " + nonExistentQuizId + " not found");
    }

    @Test
    @DisplayName("setStatus: Transitioning from PUBLISHED back to DRAFT should work")
    void setStatus_publishedToDraft_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz publishedQuiz = createValidQuizForPublishing();
        publishedQuiz.setStatus(QuizStatus.PUBLISHED);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(publishedQuiz));
        when(quizRepository.save(publishedQuiz)).thenReturn(publishedQuiz);
        when(quizMapper.toDto(publishedQuiz)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(publishedQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        verify(quizRepository).save(publishedQuiz);
    }

    @Test
    @DisplayName("setStatus: Publishing already published quiz should work (idempotent)")
    void setStatus_publishingAlreadyPublishedQuiz_isIdempotent() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz alreadyPublishedQuiz = createValidQuizForPublishing();
        alreadyPublishedQuiz.setStatus(QuizStatus.PUBLISHED);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(alreadyPublishedQuiz));
        when(quizRepository.save(alreadyPublishedQuiz)).thenReturn(alreadyPublishedQuiz);
        when(quizMapper.toDto(alreadyPublishedQuiz)).thenReturn(expectedDto);
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(alreadyPublishedQuiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(alreadyPublishedQuiz);
        verify(questionHandler, times(2)).validateContent(any()); // Called once for each question
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
} 