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
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizAssemblyService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;

import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Targeted tests for QuizServiceImpl.createQuizCollectionFromGeneratedQuestions delegation.
 * 
 * NOTE: After refactoring, QuizServiceImpl delegates to QuizGenerationFacade.
 * The actual implementation logic is tested in QuizGenerationFacadeImplBillingTest.
 * These tests verify the delegation works correctly.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("QuizServiceImpl Quiz Collection Tests")
class QuizServiceImplQuizCollectionTest {

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
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private QuizJobProperties quizJobProperties;
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

    private QuizGenerationJob testJob;
    private GenerateQuizFromDocumentRequest testRequest;
    private UUID jobId;
    private User testUser;
    private Category testCategory;

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
        
        testUser = new User();
        testUser.setUsername("testuser");
        
        testJob = new QuizGenerationJob();
        testJob.setId(jobId);
        testJob.setUser(testUser);
        testJob.setDocumentId(UUID.randomUUID());
        testJob.setStatus(GenerationStatus.PROCESSING);
        
        testCategory = new Category();
        testCategory.setId(UUID.randomUUID());
        testCategory.setName("AI Generated");
        
        testRequest = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(),
                null, // quizScope
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Test Quiz",
                "Test Description",
                Map.of(QuestionType.MCQ_SINGLE, 5),
                Difficulty.MEDIUM,
                1, // estimatedTimePerQuestion
                UUID.randomUUID(),
                List.of()
        );
        
        // Default mocks
        lenient().when(categoryRepository.findByName("AI Generated")).thenReturn(Optional.of(testCategory));
        lenient().when(tagRepository.findAllById(anyList())).thenReturn(List.of());
        
        // Mock QuizAssemblyService behavior
        lenient().when(quizAssemblyService.getOrCreateAICategory()).thenReturn(testCategory);
        lenient().when(quizAssemblyService.resolveTags(any())).thenReturn(new HashSet<>());
        lenient().when(quizAssemblyService.ensureUniqueTitle(any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        
        lenient().when(quizAssemblyService.createChunkQuiz(any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Quiz quiz = new Quiz();
                    quiz.setId(UUID.randomUUID());
                    quiz.setQuestions(new HashSet<>((List<Question>) inv.getArgument(1)));
                    return quizRepository.save(quiz);
                });
        
        lenient().when(quizAssemblyService.createConsolidatedQuiz(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    Quiz quiz = new Quiz();
                    quiz.setId(UUID.randomUUID());
                    quiz.setQuestions(new HashSet<>((List<Question>) inv.getArgument(1)));
                    return quizRepository.save(quiz);
                });
        
        // Configure facade delegation - tests will override as needed
        lenient().doNothing().when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(any(), any(), any());
    }

    @Nested
    @DisplayName("Empty List Handling Tests")
    class EmptyListHandlingTests {

        @Test
        @DisplayName("createQuizCollection: when chunkQuestions contains empty lists then filters them out")
        void createQuizCollection_emptyListsInMap_filtersThemOut() {
            // Given - Map with some empty lists
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
            chunkQuestions.put(0, createQuestions(5));
            chunkQuestions.put(1, Collections.emptyList());
            chunkQuestions.put(2, createQuestions(3));

            // When
            quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);

            // Then - Verify delegation to facade
            verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);
        }

        @Test
        @DisplayName("createQuizCollection: when chunkCount > 1 and chunk has null questions then skips it")
        void createQuizCollection_nullQuestionsInChunk_skipsIt() {
            // Given - Map with null questions
            Map<Integer, List<Question>> chunkQuestions = new LinkedHashMap<>();
            chunkQuestions.put(0, createQuestions(5));
            chunkQuestions.put(1, null);
            chunkQuestions.put(2, createQuestions(3));

            // When
            quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);

            // Then - Verify delegation to facade
            verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);
        }

        @Test
        @DisplayName("createQuizCollection: when chunkCount > 1 and chunk has empty questions then skips it")
        void createQuizCollection_emptyQuestionsInChunk_skipsIt() {
            // Given - Map with empty questions
            Map<Integer, List<Question>> chunkQuestions = new LinkedHashMap<>();
            chunkQuestions.put(0, createQuestions(5));
            chunkQuestions.put(1, Collections.emptyList());
            chunkQuestions.put(2, createQuestions(3));

            // When
            quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);

            // Then - Verify delegation to facade
            verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("createQuizCollection: when job save fails in error handler then catches and logs")
        void createQuizCollection_jobSaveFailsInErrorHandler_catchesAndLogs() {
            // Given
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
            chunkQuestions.put(0, createQuestions(5));
            
            // Configure facade to throw exception (delegation test)
            doThrow(new RuntimeException("Failed to create quiz collection from generated questions"))
                    .when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);

            // When & Then
            assertThatThrownBy(() -> 
                quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest)
            )
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to create quiz collection from generated questions");

            // Verify delegation occurred
            verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);
        }

        @Test
        @DisplayName("createQuizCollection: when exception occurs then marks job as failed")
        void createQuizCollection_exceptionOccurs_marksJobFailed() {
            // Given
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
            chunkQuestions.put(0, createQuestions(5));
            
            // Configure facade to throw exception (delegation test)
            doThrow(new RuntimeException("Quiz creation failed"))
                    .when(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);

            // When
            assertThatThrownBy(() -> 
                quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest)
            )
            .isInstanceOf(RuntimeException.class);

            // Verify delegation occurred
            verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);
        }
    }

    @Nested
    @DisplayName("Multiple Empty Chunks Test")
    class MultipleEmptyChunksTests {

        @Test
        @DisplayName("createQuizCollection: when multiple chunks are empty/null then handles gracefully")
        void createQuizCollection_multipleEmptyChunks_handlesGracefully() {
            // Given - Complex scenario with mixed empty, null, and valid chunks
            Map<Integer, List<Question>> chunkQuestions = new LinkedHashMap<>();
            chunkQuestions.put(0, createQuestions(3));
            chunkQuestions.put(1, Collections.emptyList());
            chunkQuestions.put(2, null);
            chunkQuestions.put(3, Collections.emptyList());
            chunkQuestions.put(4, createQuestions(2));
            chunkQuestions.put(5, null);

            // When
            quizService.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);

            // Then - Verify delegation to facade
            verify(quizGenerationFacade).createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, testRequest);
        }
    }

    // Helper methods

    private List<Question> createQuestions(int count) {
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            q.setType(QuestionType.MCQ_SINGLE);
            q.setQuestionText("Question " + i);
            q.setDifficulty(Difficulty.MEDIUM);
            questions.add(q);
        }
        return questions;
    }

    private Quiz createMockQuiz() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setCreator(testUser);
        return quiz;
    }
}

