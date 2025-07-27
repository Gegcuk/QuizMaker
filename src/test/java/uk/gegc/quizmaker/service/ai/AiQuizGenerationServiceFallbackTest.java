package uk.gegc.quizmaker.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import uk.gegc.quizmaker.exception.AiServiceException;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.repository.document.DocumentRepository;
import uk.gegc.quizmaker.repository.quiz.QuizGenerationJobRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.ai.impl.AiQuizGenerationServiceImpl;
import uk.gegc.quizmaker.service.ai.parser.QuestionResponseParser;
import uk.gegc.quizmaker.service.ai.PromptTemplateService;
import uk.gegc.quizmaker.service.quiz.QuizGenerationJobService;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiQuizGenerationServiceFallbackTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private QuestionResponseParser questionResponseParser;

    @Mock
    private QuizGenerationJobRepository jobRepository;

    @Mock
    private QuizGenerationJobService jobService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AiQuizGenerationServiceImpl aiQuizGenerationService;

    private Question mockQuestion1;
    private Question mockQuestion2;
    private Question mockQuestion3;
    private DocumentChunk testChunk;

    @BeforeEach
    void setUp() {
        // Create mock questions with all required fields
        mockQuestion1 = new Question();
        mockQuestion1.setId(UUID.randomUUID());
        mockQuestion1.setType(QuestionType.MCQ_SINGLE);
        mockQuestion1.setQuestionText("Test question 1");
        mockQuestion1.setDifficulty(Difficulty.MEDIUM);

        mockQuestion2 = new Question();
        mockQuestion2.setId(UUID.randomUUID());
        mockQuestion2.setType(QuestionType.MCQ_SINGLE);
        mockQuestion2.setQuestionText("Test question 2");
        mockQuestion2.setDifficulty(Difficulty.MEDIUM);

        mockQuestion3 = new Question();
        mockQuestion3.setId(UUID.randomUUID());
        mockQuestion3.setType(QuestionType.TRUE_FALSE);
        mockQuestion3.setQuestionText("Test question 3");
        mockQuestion3.setDifficulty(Difficulty.MEDIUM);

        // Create test chunk
        testChunk = new DocumentChunk();
        testChunk.setId(UUID.randomUUID());
        testChunk.setChunkIndex(1);
        testChunk.setContent("This is a comprehensive test chunk content about machine learning algorithms and their applications in artificial intelligence. The content is long enough to generate meaningful questions.");
    }

    @Nested
    class HelperMethodsTest {

        @Test
        void getEasierDifficulty_shouldReturnCorrectDifficulty() throws Exception {
            // Access private method using reflection
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod("getEasierDifficulty", Difficulty.class);
            method.setAccessible(true);

            // Test HARD -> MEDIUM
            Difficulty result = (Difficulty) method.invoke(aiQuizGenerationService, Difficulty.HARD);
            assertEquals(Difficulty.MEDIUM, result);

            // Test MEDIUM -> EASY
            result = (Difficulty) method.invoke(aiQuizGenerationService, Difficulty.MEDIUM);
            assertEquals(Difficulty.EASY, result);

            // Test EASY -> EASY (already easiest)
            result = (Difficulty) method.invoke(aiQuizGenerationService, Difficulty.EASY);
            assertEquals(Difficulty.EASY, result);
        }

        @Test
        void findAlternativeQuestionType_shouldReturnCorrectAlternatives() throws Exception {
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod("findAlternativeQuestionType", QuestionType.class);
            method.setAccessible(true);

            // Test various question type alternatives
            assertEquals(QuestionType.MCQ_SINGLE, method.invoke(aiQuizGenerationService, QuestionType.ORDERING));
            assertEquals(QuestionType.MCQ_SINGLE, method.invoke(aiQuizGenerationService, QuestionType.HOTSPOT));
            assertEquals(QuestionType.TRUE_FALSE, method.invoke(aiQuizGenerationService, QuestionType.COMPLIANCE));
            assertEquals(QuestionType.OPEN, method.invoke(aiQuizGenerationService, QuestionType.FILL_GAP));
            assertEquals(QuestionType.TRUE_FALSE, method.invoke(aiQuizGenerationService, QuestionType.OPEN));
            assertEquals(QuestionType.MCQ_SINGLE, method.invoke(aiQuizGenerationService, QuestionType.TRUE_FALSE));
            assertEquals(QuestionType.TRUE_FALSE, method.invoke(aiQuizGenerationService, QuestionType.MCQ_SINGLE));
            assertEquals(QuestionType.MCQ_SINGLE, method.invoke(aiQuizGenerationService, QuestionType.MCQ_MULTI));
        }

        @Test
        void findMissingQuestionTypes_shouldIdentifyMissingTypes() throws Exception {
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod("findMissingQuestionTypes", Map.class, Map.class);
            method.setAccessible(true);

            // Given
            Map<QuestionType, Integer> requested = new EnumMap<>(QuestionType.class);
            requested.put(QuestionType.MCQ_SINGLE, 5);
            requested.put(QuestionType.TRUE_FALSE, 3);
            requested.put(QuestionType.OPEN, 2);

            Map<QuestionType, Integer> generated = new EnumMap<>(QuestionType.class);
            generated.put(QuestionType.MCQ_SINGLE, 5); // Complete
            generated.put(QuestionType.TRUE_FALSE, 1); // Missing 2
            generated.put(QuestionType.OPEN, 0); // Missing 2

            // When
            @SuppressWarnings("unchecked")
            Map<QuestionType, Integer> result = (Map<QuestionType, Integer>) method.invoke(aiQuizGenerationService, requested, generated);

            // Then
            assertEquals(2, result.size());
            assertEquals(2, result.get(QuestionType.TRUE_FALSE));
            assertEquals(2, result.get(QuestionType.OPEN));
            assertNull(result.get(QuestionType.MCQ_SINGLE)); // Should not be in missing
        }

        @Test
        void formatCoverageSummary_shouldFormatCorrectly() throws Exception {
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod("formatCoverageSummary", Map.class, Map.class);
            method.setAccessible(true);

            // Given
            Map<QuestionType, Integer> generated = new EnumMap<>(QuestionType.class);
            generated.put(QuestionType.MCQ_SINGLE, 5);
            generated.put(QuestionType.TRUE_FALSE, 2);
            generated.put(QuestionType.OPEN, 0);

            Map<QuestionType, Integer> requested = new EnumMap<>(QuestionType.class);
            requested.put(QuestionType.MCQ_SINGLE, 5);
            requested.put(QuestionType.TRUE_FALSE, 3);
            requested.put(QuestionType.OPEN, 2);

            // When
            String result = (String) method.invoke(aiQuizGenerationService, generated, requested);

            // Then
            assertNotNull(result);
            assertTrue(result.contains("MCQ_SINGLE: 5/5 ✓")); // Complete
            assertTrue(result.contains("TRUE_FALSE: 2/3 ⚠")); // Partial
            assertTrue(result.contains("OPEN: 0/2 ✗")); // Missing
        }
    }

    @Nested
    class FallbackStrategiesTest {

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy1Success_shouldReturnQuestions() throws Exception {
            // Given - Strategy 1 succeeds on first attempt
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");
            
            // Mock successful AI response
            mockSuccessfulAiResponse(List.of(mockQuestion1, mockQuestion2, mockQuestion3));

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 3, Difficulty.MEDIUM, 1, UUID.randomUUID());

            // Then
            assertEquals(3, result.size());
            verify(promptTemplateService, times(1)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }



        @Test
        void generateQuestionsByTypeWithFallbacks_strategy1PartialSuccess_shouldReturnPartialResults() throws Exception {
            // Given - Strategy 1 fails initially but returns partial results after retries
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            // Strategy 1: First 3 attempts fail, then internal retries return partial results (2 questions)
            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    .thenThrow(new AiServiceException("Parse failed")) // Attempt 1
                    .thenThrow(new AiServiceException("Parse failed")) // Attempt 2  
                    .thenThrow(new AiServiceException("Parse failed")) // Attempt 3
                    // Internal retries within generateQuestionsByType return 2 questions (partial success)
                    .thenReturn(List.of(mockQuestion1, mockQuestion2))
                    .thenReturn(List.of(mockQuestion1, mockQuestion2))
                    .thenReturn(List.of(mockQuestion1, mockQuestion2))
                    .thenReturn(List.of(mockQuestion1, mockQuestion2))
                    .thenReturn(List.of(mockQuestion1, mockQuestion2))
                    .thenReturn(List.of(mockQuestion1, mockQuestion2));

            // Set up ChatClient mock chain
            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 4, Difficulty.MEDIUM, 1, UUID.randomUUID());

            // Then
            assertEquals(2, result.size()); // Should return partial results from Strategy 1
            // Strategy 1: 3 failed attempts + 6 internal retries = 9 calls total
            verify(promptTemplateService, times(9)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy2Success_shouldReturnReducedCount() throws Exception {
            // Given - Strategy 1 completely fails (no partial results), Strategy 2 succeeds
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            // Strategy 1 completely fails (all attempts throw exceptions)
            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 2 succeeds with reduced count (2 questions instead of 4)
                    .thenReturn(List.of(mockQuestion1, mockQuestion2))
                    .thenReturn(List.of(mockQuestion1, mockQuestion2));

            // Set up ChatClient mock chain
            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 4, Difficulty.MEDIUM, 1, UUID.randomUUID());

            // Then
            assertEquals(2, result.size());
            // Strategy 1: 9 failed attempts (3 fallback attempts × 3 internal retries each) + Strategy 2: 1 successful attempt = 10 calls total
            verify(promptTemplateService, times(10)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy3Success_shouldReturnEasierDifficulty() throws Exception {
            // Given - Strategy 1 and 2 fail, Strategy 3 succeeds with easier difficulty
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    // Strategy 1 fails (3 attempts × 3 internal retries = 9 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 2 fails (2 attempts × 3 internal retries = 6 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 3 succeeds on first try (1 call)
                    .thenReturn(List.of(mockQuestion1, mockQuestion2));

            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 2, Difficulty.HARD, 1, UUID.randomUUID());

            // Then
            assertEquals(2, result.size());
            // Strategy 1: 3×3 + Strategy 2: 2×3 + Strategy 3: 1×1 (succeeds on first try) = 16 calls total
            verify(promptTemplateService, times(16)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy4Success_shouldReturnAlternativeType() throws Exception {
            // Given - Strategies 1-3 fail, Strategy 4 succeeds with alternative type
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    // Strategy 1 fails (3 attempts × 3 internal retries = 9 calls)
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 1
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 2
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 3
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 4
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 5
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 6
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 7
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 8
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 9
                    // Strategy 2 is SKIPPED (questionCount = 1)
                    // Strategy 3 fails (1 attempt × 3 internal retries = 3 calls)
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 10
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 11
                    .thenThrow(new AiServiceException("Parse failed"))  // Call 12
                    // Strategy 4 succeeds on first try (1 call) - HOTSPOT -> MCQ_SINGLE alternative
                    .thenReturn(List.of(mockQuestion1)) // Call 13 - MCQ_SINGLE question (alternative for HOTSPOT)
                    // Add extra responses in case of unexpected calls
                    .thenReturn(List.of(mockQuestion1)) // Call 14
                    .thenReturn(List.of(mockQuestion1)) // Call 15
                    .thenReturn(List.of(mockQuestion1)) // Call 16
                    .thenReturn(List.of(mockQuestion1)) // Call 17
                    .thenReturn(List.of(mockQuestion1)); // Call 18

            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 1, Difficulty.MEDIUM, 1, UUID.randomUUID());

            // Then
            assertEquals(1, result.size());
            assertEquals(QuestionType.MCQ_SINGLE, result.get(0).getType());
            // Strategy 1: 3×3 + Strategy 2: SKIPPED + Strategy 3: 1×3 + Strategy 4: 1×1 (succeeds on first try) = 13 calls total
            verify(promptTemplateService, times(13)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy5Success_shouldReturnMCQSingle() throws Exception {
            // Given - Strategies 1-4 fail, Strategy 5 (last resort MCQ) succeeds
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    // Strategy 1 fails (3 attempts × 3 internal retries = 9 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 2 is SKIPPED (questionCount = 1)
                    // Strategy 3 fails (1 attempt × 3 internal retries = 3 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 4 fails (1 attempt × 3 internal retries = 3 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 5 succeeds on first try (1 call)
                    .thenReturn(List.of(mockQuestion1))
                    // Add many extra responses to handle any unexpected calls
                    .thenReturn(List.of(mockQuestion1))
                    .thenReturn(List.of(mockQuestion1))
                    .thenReturn(List.of(mockQuestion1))
                    .thenReturn(List.of(mockQuestion1))
                    .thenReturn(List.of(mockQuestion1));

            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 1, Difficulty.MEDIUM, 1, UUID.randomUUID());

            // Then
            assertEquals(1, result.size());
            assertEquals(QuestionType.MCQ_SINGLE, result.get(0).getType());
            // Strategy 1: 3×3 + Strategy 2: SKIPPED + Strategy 3: 1×3 + Strategy 4: 1×3 + Strategy 5: 1×1 (succeeds on first try) = 16 calls total
            verify(promptTemplateService, times(16)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_allStrategiesFail_shouldReturnEmptyList() throws Exception {
            // Given - All strategies fail
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            // All strategies fail completely (24 total calls)
            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Add many extra exceptions to handle any unexpected calls
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"));

            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 1, Difficulty.MEDIUM, 1, UUID.randomUUID());

            // Then
            assertTrue(result.isEmpty());
            // Should try all strategies: 3×3 + SKIPPED + 1×3 + 1×3 + 1×3 = 18 attempts (each strategy × 3 internal retries)
            verify(promptTemplateService, times(18)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_singleQuestionCount_shouldSkipStrategy2() throws Exception {
            // Given - Single question request should skip strategy 2 (reduced count)
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    // Strategy 1 fails (3 attempts × 3 internal retries = 9 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 2 is skipped for single question
                    // Strategy 3 succeeds on first try (1 call)
                    .thenReturn(List.of(mockQuestion1));

            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 1, Difficulty.HARD, 1, UUID.randomUUID());

            // Then
            assertEquals(1, result.size());
            // Should try: 3×3 (strategy 1) + 0 (strategy 2 skipped) + 1×1 (strategy 3 succeeds on first try) = 10 attempts
            verify(promptTemplateService, times(10)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_easyDifficulty_shouldSkipStrategy3() throws Exception {
            // Given - Easy difficulty should skip strategy 3 (easier difficulty)
            when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any()))
                    .thenReturn("test prompt");

            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any()))
                    // Strategy 1 fails (3 attempts × 3 internal retries = 9 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 2 fails (2 attempts × 3 internal retries = 6 calls)
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    .thenThrow(new AiServiceException("Parse failed"))
                    // Strategy 3 is skipped for EASY difficulty
                    // Strategy 4 succeeds on first try (1 call) - return 2 questions to match request
                    .thenReturn(List.of(mockQuestion3, mockQuestion3));

            mockChatClientChain();

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 2, Difficulty.EASY, 1, UUID.randomUUID());

            // Then
            assertEquals(2, result.size());
            // Should try: 3×3 (strategy 1) + 2×3 (strategy 2) + 0 (strategy 3 skipped) + 1×1 (strategy 4 succeeds on first try) = 16 attempts
            verify(promptTemplateService, times(16)).buildPromptForChunk(anyString(), any(), anyInt(), any());
        }
    }

    // Helper method to mock successful AI response
    private void mockSuccessfulAiResponse(List<Question> questions) throws Exception {
        // Always set up the ChatClient mock chain, even when questions is null
        mockChatClientChain();

        // Only set up the parser mock if questions is provided
        if (questions != null) {
            when(questionResponseParser.parseQuestionsFromAIResponse(anyString(), any())).thenReturn(questions);
        }
    }

    // Helper method to set up ChatClient mock chain without interfering with parser mocks
    private void mockChatClientChain() {
        org.springframework.ai.chat.client.ChatClient.CallResponseSpec callResponseSpec = mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec requestSpec = mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse = mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation = mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage assistantMessage = mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
                 when(assistantMessage.getText()).thenReturn("Mock AI response");
     }
 }  