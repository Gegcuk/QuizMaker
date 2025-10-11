package uk.gegc.quizmaker.service.ai;

import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.application.impl.AiQuizGenerationServiceImpl;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiQuizGenerationServiceFallbackTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private QuestionResponseParser questionResponseParser;

    @Mock
    private AiRateLimitConfig rateLimitConfig;

    @Mock
    private Logger aiResponseLogger;
    
    @Mock
    private InternalBillingService internalBillingService;
    
    @Mock
    private StructuredAiClient structuredAiClient;

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

    private void setupRateLimitConfig() {
        // Set up rate limit configuration for tests that need it
        lenient().when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        lenient().when(rateLimitConfig.getBaseDelayMs()).thenReturn(1000L);
        lenient().when(rateLimitConfig.getMaxDelayMs()).thenReturn(10000L);
        lenient().when(rateLimitConfig.getJitterFactor()).thenReturn(0.25);
    }

    private void setupLoggerStubbing() {
        // Configure logger mock to handle info calls
        lenient().doNothing().when(aiResponseLogger).info(anyString());
        lenient().doNothing().when(aiResponseLogger).info(anyString(), any(Object.class));
        lenient().doNothing().when(aiResponseLogger).info(anyString(), any(Object.class), any(Object.class));
        lenient().doNothing().when(aiResponseLogger).info(anyString(), any(Object.class), any(Object.class), any(Object.class));
        lenient().doNothing().when(aiResponseLogger).info(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class));
    }

    @Nested
    class HelperMethodsTest {

        @BeforeEach
        void setUpHelperMethods() {
            // Helper methods don't use logger, so no logger stubbing needed
            // Only set up the test data that's needed
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

            testChunk = new DocumentChunk();
            testChunk.setId(UUID.randomUUID());
            testChunk.setChunkIndex(1);
            testChunk.setContent("This is a comprehensive test chunk content about machine learning algorithms and their applications in artificial intelligence. The content is long enough to generate meaningful questions.");
        }

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

        /**
         * Helper to create a StructuredQuestionResponse from domain Questions
         */
        private StructuredQuestionResponse createStructuredResponse(List<Question> questions) {
            List<StructuredQuestion> structuredQuestions = questions.stream()
                    .map(q -> {
                        StructuredQuestion sq = new StructuredQuestion();
                        sq.setQuestionText(q.getQuestionText());
                        sq.setType(q.getType());
                        sq.setDifficulty(q.getDifficulty());
                        sq.setContent("{}");  // Mock content
                        return sq;
                    })
                    .toList();
            
            return StructuredQuestionResponse.builder()
                    .questions(structuredQuestions)
                    .warnings(List.of())
                    .tokensUsed(100L)
                    .build();
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy1Success_shouldReturnQuestions() throws Exception {
            // Given - Strategy 1 succeeds on first attempt (Phase 3: uses StructuredAiClient)
            setupRateLimitConfig();
            setupLoggerStubbing();
            
            // Mock successful structured AI response
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenReturn(createStructuredResponse(List.of(mockQuestion1, mockQuestion2, mockQuestion3)));

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 3, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            // Then
            assertEquals(3, result.size());
            verify(structuredAiClient, times(1)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy1PartialSuccess_shouldReturnPartialResults() throws Exception {
            // Given - Strategy 1 returns partial results on last attempt (2 questions instead of 4)
            setupRateLimitConfig();
            setupLoggerStubbing();
            
            // Attempts 1-2 fail, attempt 3 returns partial results
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Generation failed")) // Attempt 1
                    .thenThrow(new AiServiceException("Generation failed")) // Attempt 2
                    .thenReturn(createStructuredResponse(List.of(mockQuestion1, mockQuestion2))); // Attempt 3: partial success

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 4, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            // Then - Strategy 1 should return 2 questions (partial success on attempt 3)
            assertEquals(2, result.size());
            verify(structuredAiClient, times(3)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy2Success_shouldReturnReducedCount() throws Exception {
            // Given - Strategy 1 fails all 3 attempts, Strategy 2 succeeds with reduced count
            setupRateLimitConfig();
            setupLoggerStubbing();

            // Strategy 1: All 3 attempts fail
            // Strategy 2: First attempt with reduced count (2 instead of 4) succeeds
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed")) // Strategy 1, attempt 1
                    .thenThrow(new AiServiceException("Failed")) // Strategy 1, attempt 2
                    .thenThrow(new AiServiceException("Failed")) // Strategy 1, attempt 3
                    .thenReturn(createStructuredResponse(List.of(mockQuestion1, mockQuestion2))); // Strategy 2, attempt 1 (reduced count)

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 4, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            // Then - Strategy 2 should return 2 questions (reduced count)
            assertEquals(2, result.size());
            verify(structuredAiClient, times(4)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy3Success_shouldReturnEasierDifficulty() throws Exception {
            // Given - Strategies 1 and 2 fail, Strategy 3 succeeds with easier difficulty
            setupRateLimitConfig();
            setupLoggerStubbing();

            // Strategy 1: 3 attempts fail
            // Strategy 2: 2 attempts fail (reduced count)
            // Strategy 3: Succeeds with easier difficulty
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed")) // Strategy 1, attempt 1
                    .thenThrow(new AiServiceException("Failed")) // Strategy 1, attempt 2
                    .thenThrow(new AiServiceException("Failed")) // Strategy 1, attempt 3
                    .thenThrow(new AiServiceException("Failed")) // Strategy 2, attempt 1
                    .thenThrow(new AiServiceException("Failed")) // Strategy 2, attempt 2
                    .thenReturn(createStructuredResponse(List.of(mockQuestion1, mockQuestion2))); // Strategy 3

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 2, Difficulty.HARD, 1, UUID.randomUUID(), "en");

            // Then - Strategy 3 should return 2 questions with easier difficulty
            assertEquals(2, result.size());
            verify(structuredAiClient, times(6)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy4Success_shouldReturnAlternativeType() throws Exception {
            // Given - Strategies 1-3 fail, Strategy 4 succeeds with alternative type
            setupRateLimitConfig();
            setupLoggerStubbing();

            // Strategy 1: 3 attempts fail
            // Strategy 2: SKIPPED (questionCount = 1)
            // Strategy 3: 1 attempt fails (easier difficulty)
            // Strategy 4: Succeeds with alternative type (TRUE_FALSE instead of ORDERING)
            mockQuestion1.setType(QuestionType.MCQ_SINGLE); // Alternative type result
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 1
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 2
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 3
                    // Strategy 2 is SKIPPED (questionCount = 1)
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 3 (easier difficulty)
                    .thenReturn(createStructuredResponse(List.of(mockQuestion1))); // Strategy 4: alternative type succeeds

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 1, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            // Then - Strategy 4 should return alternative question type
            assertEquals(1, result.size());
            assertEquals(QuestionType.MCQ_SINGLE, result.get(0).getType());
            verify(structuredAiClient, times(5)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_strategy5Success_shouldReturnMCQSingle() throws Exception {
            // Given - Strategies 1-4 fail, Strategy 5 (last resort MCQ_SINGLE) succeeds
            setupRateLimitConfig();
            setupLoggerStubbing();

            // Strategy 1: 3 attempts fail
            // Strategy 2: SKIPPED (questionCount = 1)
            // Strategy 3: 1 attempt fails (easier difficulty)
            // Strategy 4: 1 attempt fails (alternative type)
            // Strategy 5: Last resort MCQ_SINGLE succeeds
            mockQuestion1.setType(QuestionType.MCQ_SINGLE); // Last resort type
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 1
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 2
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 3
                    // Strategy 2 is SKIPPED (questionCount = 1)
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 3 (easier difficulty)
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 4 (alternative type)
                    .thenReturn(createStructuredResponse(List.of(mockQuestion1))); // Strategy 5: last resort

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.COMPLIANCE, 1, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            // Then - Strategy 5 should return MCQ_SINGLE (last resort)
            assertEquals(1, result.size());
            assertEquals(QuestionType.MCQ_SINGLE, result.get(0).getType());
            verify(structuredAiClient, times(6)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_allStrategiesFail_shouldReturnEmptyList() throws Exception {
            // Given - All 5 strategies fail
            setupRateLimitConfig();
            setupLoggerStubbing();

            // All strategies fail completely
            // Strategy 1: 3 attempts
            // Strategy 2: SKIPPED (questionCount = 1)
            // Strategy 3: 1 attempt
            // Strategy 4: 1 attempt
            // Strategy 5: 1 attempt
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed")); // All attempts fail

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 1, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            // Then - Should return empty list
            assertTrue(result.isEmpty());
            // Should try all strategies: 3 (strat1) + 0 (strat2 skipped) + 1 (strat3) + 1 (strat4) + 1 (strat5) = 6 attempts
            verify(structuredAiClient, times(6)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_singleQuestionCount_shouldSkipStrategy2() throws Exception{
            // Given - Single question request should skip strategy 2 (reduced count)
            setupRateLimitConfig();
            setupLoggerStubbing();

            // Strategy 1: 3 attempts fail
            // Strategy 2: SKIPPED (questionCount = 1, can't reduce)
            // Strategy 3: Succeeds (easier difficulty)
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 1
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 2
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 3
                    // Strategy 2 is skipped (questionCount = 1)
                    .thenReturn(createStructuredResponse(List.of(mockQuestion1))); // Strategy 3: easier difficulty

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 1, Difficulty.HARD, 1, UUID.randomUUID(), "en");

            // Then - Strategy 3 succeeds, Strategy 2 was skipped
            assertEquals(1, result.size());
            // Should try: 3 (strategy 1) + 0 (strategy 2 skipped) + 1 (strategy 3) = 4 attempts
            verify(structuredAiClient, times(4)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        void generateQuestionsByTypeWithFallbacks_easyDifficulty_shouldSkipStrategy3() throws Exception {
            // Given - Easy difficulty should skip strategy 3 (easier difficulty)
            setupRateLimitConfig();
            setupLoggerStubbing();

            // Strategy 1: 3 attempts fail
            // Strategy 2: 2 attempts fail (reduced count)
            // Strategy 3: SKIPPED (already EASY difficulty)
            // Strategy 4: Succeeds (alternative type)
            mockQuestion3.setType(QuestionType.TRUE_FALSE); // Alternative type
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 1
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 2
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 1, attempt 3
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 2, attempt 1
                    .thenThrow(new AiServiceException("Failed"))  // Strategy 2, attempt 2
                    // Strategy 3 is skipped (difficulty is EASY)
                    .thenReturn(createStructuredResponse(List.of(mockQuestion3, mockQuestion3))); // Strategy 4: alternative type

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 2, Difficulty.EASY, 1, UUID.randomUUID(), "en");

            // Then - Strategy 4 succeeds, Strategy 3 was skipped
            assertEquals(2, result.size());
            // Should try: 3 (strategy 1) + 2 (strategy 2) + 0 (strategy 3 skipped) + 1 (strategy 4) = 6 attempts
            verify(structuredAiClient, times(6)).generateQuestions(any(StructuredQuestionRequest.class));
        }
    }

    // Helper method to mock successful AI response
    private void mockSuccessfulAiResponse(List<Question> questions) {
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