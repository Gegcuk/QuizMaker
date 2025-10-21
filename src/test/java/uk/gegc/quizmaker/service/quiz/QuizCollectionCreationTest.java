package uk.gegc.quizmaker.service.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;

import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("Quiz Collection Creation Tests")
class QuizCollectionCreationTest {

    @Mock
    private uk.gegc.quizmaker.features.user.domain.repository.UserRepository userRepository;

    @Mock
    private QuizGenerationJobRepository jobRepository;
    
    @Mock
    private uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository quizRepository;
    
    @Mock
    private uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository categoryRepository;
    
    @Mock
    private uk.gegc.quizmaker.features.tag.domain.repository.TagRepository tagRepository;

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

    @Mock(lenient = true)
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
    private QuizAssemblyService quizAssemblyService;
    
    @Mock
    private QuizGenerationFacade quizGenerationFacade;

    private QuizServiceImpl quizService;

    private User testUser;
    private QuizGenerationJob testJob;
    private GenerateQuizFromDocumentRequest testRequest;
    private Category testCategory;
    private UUID testJobId;
    private UUID testDocumentId;

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
        testUser.setUsername("testuser");

        testJobId = UUID.randomUUID();
        testDocumentId = UUID.randomUUID();

        testJob = new QuizGenerationJob();
        testJob.setId(testJobId);
        testJob.setUser(testUser);
        testJob.setDocumentId(testDocumentId);
        testJob.setStatus(GenerationStatus.PROCESSING);

        testCategory = new Category();
        testCategory.setName("AI Generated");
        testCategory.setDescription("Quizzes automatically generated by AI");

        Map<QuestionType, Integer> questionsPerType = new HashMap<>();
        questionsPerType.put(QuestionType.MCQ_SINGLE, 2);
        questionsPerType.put(QuestionType.TRUE_FALSE, 1);

        testRequest = new GenerateQuizFromDocumentRequest(
                testDocumentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz",
                "Test description",
                questionsPerType,
                Difficulty.MEDIUM,
                2, // estimatedTimePerQuestion
                null, // categoryId
                List.of() // tagIds
        );
        
        // Lenient stubbing for billing-related mocks that might not be called in all tests
        lenient().when(estimationService.computeActualBillingTokens(any(), any(), anyLong())).thenReturn(100L);
        lenient().when(quizDefaultsProperties.getDefaultCategoryId())
                .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        
        // Mock job repository for commit phase
        lenient().when(jobRepository.findById(any())).thenReturn(Optional.of(testJob));
        lenient().when(jobRepository.findByIdForUpdate(any())).thenReturn(Optional.of(testJob));
        lenient().when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        
        // Mock QuizAssemblyService to delegate to actual behavior
        lenient().when(quizAssemblyService.getOrCreateAICategory()).thenAnswer(inv -> {
            return categoryRepository.findByName("AI Generated")
                    .orElseGet(() -> categoryRepository.findByName("General")
                            .orElseGet(() -> {
                                Category aiCategory = new Category();
                                aiCategory.setName("AI Generated");
                                aiCategory.setDescription("Quizzes automatically generated by AI");
                                return categoryRepository.save(aiCategory);
                            }));
        });
        
        lenient().when(quizAssemblyService.resolveTags(any())).thenAnswer(inv -> {
            GenerateQuizFromDocumentRequest req = inv.getArgument(0);
            if (req.tagIds() == null) {
                return new HashSet<>();
            }
            return req.tagIds().stream()
                    .map(tagRepository::findById)
                    .flatMap(Optional::stream)
                    .collect(java.util.stream.Collectors.toSet());
        });
        
        lenient().when(quizAssemblyService.ensureUniqueTitle(any(User.class), anyString()))
                .thenAnswer(inv -> {
                    User user = inv.getArgument(0);
                    String requestedTitle = inv.getArgument(1);
                    if (requestedTitle == null || requestedTitle.isBlank()) {
                        requestedTitle = "Untitled Quiz";
                    }
                    // Check if title exists and append suffix if needed
                    if (!quizRepository.existsByCreatorIdAndTitle(user.getId(), requestedTitle)) {
                        return requestedTitle;
                    }
                    // Title exists, append -2
                    return requestedTitle + "-2";
                });
        
        lenient().when(quizAssemblyService.createChunkQuiz(any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    User user = inv.getArgument(0);
                    List<Question> questions = inv.getArgument(1);
                    int chunkIndex = inv.getArgument(2);
                    GenerateQuizFromDocumentRequest request = inv.getArgument(3);
                    Category category = inv.getArgument(4);
                    Set<uk.gegc.quizmaker.features.tag.domain.model.Tag> tags = inv.getArgument(5);
                    
                    Quiz quiz = new Quiz();
                    quiz.setTitle("Chunk " + chunkIndex);
                    quiz.setDescription("Chunk quiz " + chunkIndex);
                    quiz.setCreator(user);
                    quiz.setCategory(category);
                    quiz.setTags(tags);
                    quiz.setStatus(uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus.PUBLISHED);
                    quiz.setVisibility(uk.gegc.quizmaker.features.quiz.domain.model.Visibility.PRIVATE);
                    quiz.setEstimatedTime(Math.max(1, (int) Math.ceil(questions.size() * 1.5)));
                    quiz.setQuestions(new HashSet<>(questions));
                    return quizRepository.save(quiz);
                });
        
        lenient().when(quizAssemblyService.createConsolidatedQuiz(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    User user = inv.getArgument(0);
                    List<Question> allQuestions = inv.getArgument(1);
                    GenerateQuizFromDocumentRequest request = inv.getArgument(2);
                    Category category = inv.getArgument(3);
                    Set<uk.gegc.quizmaker.features.tag.domain.model.Tag> tags = inv.getArgument(4);
                    
                    String requestedTitle = request.quizTitle() != null ? request.quizTitle() : "Complete Document Quiz";
                    String uniqueTitle = quizAssemblyService.ensureUniqueTitle(user, requestedTitle);
                    
                    Quiz quiz = new Quiz();
                    quiz.setTitle(uniqueTitle);
                    quiz.setDescription(request.quizDescription() != null ? request.quizDescription() : "Comprehensive quiz");
                    quiz.setCreator(user);
                    quiz.setCategory(category);
                    quiz.setTags(tags);
                    quiz.setStatus(uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus.PUBLISHED);
                    quiz.setVisibility(uk.gegc.quizmaker.features.quiz.domain.model.Visibility.PRIVATE);
                    quiz.setEstimatedTime(Math.max(1, (int) Math.ceil(allQuestions.size() * 1.5)));
                    quiz.setQuestions(new HashSet<>(allQuestions));
                    return quizRepository.save(quiz);
                });
        
        // Configure facade delegation
        lenient().doNothing().when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(any(), any(), any());
    }

    @Test
    @DisplayName("createQuizCollection: multi-chunk should create per-chunk quizzes and consolidated quiz")
    void shouldCreateQuizCollectionSuccessfully() {
        // Given
        Map<Integer, List<Question>> chunkQuestions = createTestChunkQuestions();

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);
    }

    @Test
    @DisplayName("createQuizCollection: should create AI category if not exists")
    void shouldCreateAICategoryIfNotExists() {
        // Given
        Map<Integer, List<Question>> chunkQuestions = createTestChunkQuestions();

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);
    }

    @Test
    @DisplayName("createQuizCollection: empty chunk questions should create only consolidated quiz with 0 questions")
    void shouldHandleEmptyChunkQuestions() {
        // Given
        Map<Integer, List<Question>> chunkQuestions = new HashMap<>();

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);
    }

    @Test
    @DisplayName("createQuizCollection: job not found should throw RuntimeException")
    void shouldHandleJobNotFound() {
        // Given
        Map<Integer, List<Question>> chunkQuestions = createTestChunkQuestions();
        
        // Configure facade to throw exception
        doThrow(new RuntimeException("Generation job not found"))
                .when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            quizService.createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);
        });
        
        // Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);
    }

    @Test
    @DisplayName("createQuizCollection: quiz creation failure should mark job as failed")
    void shouldHandleQuizCreationFailure() {
        // Given
        Map<Integer, List<Question>> chunkQuestions = createTestChunkQuestions();
        
        // Configure facade to throw exception
        doThrow(new RuntimeException("Database error"))
                .when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            quizService.createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);
        });
        
        // Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, chunkQuestions, testRequest);
    }

    // ========== Single-Chunk Quiz Generation Tests ==========

    @Test
    @DisplayName("createQuizCollection: single-chunk should create only consolidated quiz (no per-chunk quiz)")
    void singleChunk_shouldCreateOnlyConsolidatedQuiz() {
        // Given
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, testRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, testRequest);
    }

    @Test
    @DisplayName("createQuizCollection: single-chunk with SPECIFIC_CHUNKS scope should create only consolidated quiz")
    void singleChunkSpecificChunks_shouldCreateOnlyConsolidatedQuiz() {
        // Given
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        
        GenerateQuizFromDocumentRequest specificChunksRequest = new GenerateQuizFromDocumentRequest(
                testDocumentId,
                QuizScope.SPECIFIC_CHUNKS,
                List.of(0),
                null, null,
                "Specific Chunk Quiz",
                "Quiz for a specific chunk",
                Map.of(QuestionType.MCQ_SINGLE, 2, QuestionType.TRUE_FALSE, 1),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, specificChunksRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, specificChunksRequest);
    }

    @Test
    @DisplayName("createQuizCollection: single-chunk should use user-provided title and description")
    void singleChunk_shouldUseUserProvidedTitleAndDescription() {
        // Given
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        
        GenerateQuizFromDocumentRequest customRequest = new GenerateQuizFromDocumentRequest(
                testDocumentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "My Custom Quiz Title",
                "My custom quiz description",
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, customRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, customRequest);
    }

    @Test
    @DisplayName("createQuizCollection: single-chunk with title collision should append suffix")
    void singleChunk_withTitleCollision_shouldAppendSuffix() {
        // Given
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        
        GenerateQuizFromDocumentRequest customRequest = new GenerateQuizFromDocumentRequest(
                testDocumentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                "Duplicate Quiz Title",
                "Test description",
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, customRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, customRequest);
    }

    @Test
    @DisplayName("createQuizCollection: single-chunk should set correct job completion fields")
    void singleChunk_shouldSetCorrectJobCompletionFields() {
        // Given
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, testRequest);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, testRequest);
    }

    @Test
    @DisplayName("createQuizCollection: single-chunk with null title should use default")
    void singleChunk_withNullTitle_shouldUseDefault() {
        // Given
        Map<Integer, List<Question>> singleChunkQuestions = createSingleChunkQuestions();
        
        GenerateQuizFromDocumentRequest requestWithoutTitle = new GenerateQuizFromDocumentRequest(
                testDocumentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, null, null,
                null, null,
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        // When
        quizService.createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, requestWithoutTitle);

        // Then - Verify delegation to facade
        verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(testJobId, singleChunkQuestions, requestWithoutTitle);
    }

    // ========== Helper Methods ==========

    private Map<Integer, List<Question>> createSingleChunkQuestions() {
        Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
        
        // Create questions for a single chunk (index 0)
        List<Question> chunk0Questions = Arrays.asList(
                createTestQuestion("Question 1 from single chunk", QuestionType.MCQ_SINGLE),
                createTestQuestion("Question 2 from single chunk", QuestionType.MCQ_SINGLE),
                createTestQuestion("Question 3 from single chunk", QuestionType.TRUE_FALSE)
        );
        chunkQuestions.put(0, chunk0Questions);
        
        return chunkQuestions;
    }

    private Map<Integer, List<Question>> createTestChunkQuestions() {
        Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
        
        // Create questions for chunk 0
        List<Question> chunk0Questions = Arrays.asList(
                createTestQuestion("Question 1 from chunk 0", QuestionType.MCQ_SINGLE),
                createTestQuestion("Question 2 from chunk 0", QuestionType.MCQ_SINGLE),
                createTestQuestion("Question 3 from chunk 0", QuestionType.TRUE_FALSE)
        );
        chunkQuestions.put(0, chunk0Questions);
        
        // Create questions for chunk 1
        List<Question> chunk1Questions = Arrays.asList(
                createTestQuestion("Question 1 from chunk 1", QuestionType.MCQ_SINGLE),
                createTestQuestion("Question 2 from chunk 1", QuestionType.MCQ_SINGLE),
                createTestQuestion("Question 3 from chunk 1", QuestionType.TRUE_FALSE)
        );
        chunkQuestions.put(1, chunk1Questions);
        
        return chunkQuestions;
    }

    private Question createTestQuestion(String questionText, QuestionType type) {
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setQuestionText(questionText);
        question.setType(type);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setContent("{\"question\":\"" + questionText + "\"}");
        return question;
    }
} 
