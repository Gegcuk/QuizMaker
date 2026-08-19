package uk.gegc.quizmaker.service.ai;

import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudget;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.application.impl.AiQuizGenerationServiceImpl;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContentValidationServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.FillGapHandler;
import uk.gegc.quizmaker.features.question.infra.handler.McqSingleHandler;
import uk.gegc.quizmaker.features.question.infra.handler.TrueFalseHandler;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageService;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;
import uk.gegc.quizmaker.shared.testing.DirectAiProviderTaskScheduler;
import uk.gegc.quizmaker.shared.validation.GenerationLanguagePolicy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI quiz generation fallback behavior")
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
    private AiRateLimitConfig rateLimitConfig;

    @Mock
    private QuizGenerationJobRepository jobRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Logger aiResponseLogger;
    
    @Mock
    private InternalBillingService internalBillingService;
    
    @Mock
    private StructuredAiClient structuredAiClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private QuestionContentShuffler questionContentShuffler;

    @Mock
    private ProviderUsageService providerUsageService;

    private AiQuizGenerationServiceImpl aiQuizGenerationService;

    private DocumentChunk testChunk;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuestionContentValidationService questionContentValidationService =
            new QuestionContentValidationServiceImpl(new QuestionHandlerFactory(List.of(
                    new McqSingleHandler(),
                    new TrueFalseHandler(),
                    new FillGapHandler()
            )));

    @BeforeEach
    void setUp() {
        aiQuizGenerationService = new AiQuizGenerationServiceImpl(
                chatClient,
                documentRepository,
                promptTemplateService,
                questionResponseParser,
                jobRepository,
                userRepository,
                objectMapper,
                eventPublisher,
                rateLimitConfig,
                internalBillingService,
                transactionTemplate,
                structuredAiClient,
                questionContentShuffler,
                questionContentValidationService,
                providerUsageService,
                DirectAiProviderTaskScheduler.INSTANCE
        );

        // Create test chunk
        testChunk = new DocumentChunk();
        testChunk.setId(UUID.randomUUID());
        testChunk.setChunkIndex(1);
        testChunk.setContent("This is a comprehensive test chunk content about machine learning algorithms and their applications in artificial intelligence. The content is long enough to generate meaningful questions.");
        
        // Mock QuestionContentShuffler to return content as-is (no actual shuffling in tests)
        lenient().when(questionContentShuffler.shuffleContent(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0)); // Return content unchanged
    }

    @Test
    @DisplayName("Fill-gap conversion preserves generated drag options")
    void convertStructuredQuestions_fillGapWithOptions_preservesOptionsInDomainContent() throws Exception {
        StructuredQuestion fillGapQuestion = StructuredQuestion.builder()
                .questionText("Complete the cellular respiration sentence.")
                .type(QuestionType.FILL_GAP)
                .difficulty(Difficulty.MEDIUM)
                .content("""
                        {
                          "text": "Cellular respiration occurs in the {1} and produces {2}.",
                          "gaps": [
                            {"id": 1, "answer": "mitochondria"},
                            {"id": 2, "answer": "ATP"}
                          ],
                          "options": ["mitochondria", "ATP", "chloroplast", "ribosome", "nucleus", "glucose", "NADH", "oxygen"]
                        }
                        """)
                .hint("Think about the powerhouse of the cell.")
                .explanation("Cellular respiration happens in mitochondria and produces ATP.")
                .confidence(1.0)
                .build();

        List<Question> questions = aiQuizGenerationService.convertStructuredQuestions(List.of(fillGapQuestion));

        assertEquals(1, questions.size());
        Question question = questions.get(0);
        assertEquals(QuestionType.FILL_GAP, question.getType());

        JsonNode content = objectMapper.readTree(question.getContent());
        assertTrue(content.has("options"));
        assertEquals(8, content.get("options").size());
        assertEquals("mitochondria", content.get("gaps").get(0).get("answer").asText());
        assertEquals("ATP", content.get("gaps").get(1).get("answer").asText());
        verify(questionContentShuffler).shuffleContent(anyString(), eq(QuestionType.FILL_GAP), any());
    }

    @Test
    @DisplayName("Public generation entry points reject invalid language before job work")
    void generateQuizFromDocumentAsync_rejectsInvalidLanguageBeforeJobWork() {
        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                UUID.randomUUID(), QuizScope.ENTIRE_DOCUMENT, null, null, null,
                "Biology", "A quiz", Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM, 1, null, List.of(), "en-US");
        QuizGenerationJob job = mock(QuizGenerationJob.class);

        IllegalArgumentException byId = assertThrows(IllegalArgumentException.class,
                () -> aiQuizGenerationService.generateQuizFromDocumentAsync(UUID.randomUUID(), request));
        IllegalArgumentException byJob = assertThrows(IllegalArgumentException.class,
                () -> aiQuizGenerationService.generateQuizFromDocumentAsync(job, request));
        IllegalArgumentException byChunk = assertThrows(IllegalArgumentException.class,
                () -> aiQuizGenerationService.generateQuestionsFromChunkWithJob(
                        testChunk, Map.of(QuestionType.MCQ_SINGLE, 1), Difficulty.MEDIUM,
                        UUID.randomUUID(), "en-US"));

        assertEquals(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE, byId.getMessage());
        assertEquals(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE, byJob.getMessage());
        assertEquals(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE, byChunk.getMessage());
        verifyNoInteractions(job, jobRepository, documentRepository, structuredAiClient);
    }

    private void setupRateLimitConfig() {
        // Set up rate limit configuration for tests that need it
        lenient().when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        lenient().when(rateLimitConfig.getMaxAttemptsPerTask()).thenReturn(5);
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
    @DisplayName("Coverage helper methods")
    class HelperMethodsTest {

        @Test
        @DisplayName("Coverage summary distinguishes complete, partial, and empty buckets")
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
    @DisplayName("Same-contract fallback strategies")
    class FallbackStrategiesTest {

        @Test
        @DisplayName("Invalid language fails before allocating a budget or calling the provider")
        void generateQuestionsByTypeWithFallbacks_rejectsInvalidLanguageBeforeDispatch() throws Exception {
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class,
                    Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            InvocationTargetException exception = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE,
                            2, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en-US"));

            IllegalArgumentException cause = assertInstanceOf(IllegalArgumentException.class, exception.getCause());
            assertEquals(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE, cause.getMessage());
            verifyNoInteractions(rateLimitConfig, structuredAiClient);
        }

        private StructuredQuestionResponse createStructuredResponse(
                int questionCount,
                QuestionType type,
                Difficulty difficulty) {
            List<StructuredQuestion> structuredQuestions = java.util.stream.IntStream
                    .range(0, questionCount)
                    .mapToObj(index -> StructuredQuestion.builder()
                            .questionText("Generated question " + index)
                            .type(type)
                            .difficulty(difficulty)
                            .content(validContent(type))
                            .confidence(1.0)
                            .build())
                    .toList();

            return StructuredQuestionResponse.builder()
                    .questions(structuredQuestions)
                    .warnings(List.of())
                    .tokensUsed(100L)
                    .build();
        }

        private String validContent(QuestionType type) {
            return switch (type) {
                case MCQ_SINGLE -> """
                        {
                          "options": [
                            {"id": "a", "text": "Correct answer", "correct": true},
                            {"id": "b", "text": "Distractor", "correct": false}
                          ]
                        }
                        """;
                case TRUE_FALSE -> "{\"answer\": true}";
                default -> throw new AssertionError("No fallback fixture for question type " + type);
            };
        }

        @Test
        @DisplayName("Normal generation returns requested type and difficulty")
        void generateQuestionsByTypeWithFallbacks_strategy1Success_shouldReturnQuestions() throws Exception {
            // Given - Strategy 1 succeeds on first attempt (Phase 3: uses StructuredAiClient)
            setupRateLimitConfig();
            setupLoggerStubbing();
            
            // Mock successful structured AI response
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenReturn(createStructuredResponse(3, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM));

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 3, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            // Then
            assertEquals(3, result.size());
            assertTrue(result.stream().allMatch(question -> question.getType() == QuestionType.MCQ_SINGLE));
            assertTrue(result.stream().allMatch(question -> question.getDifficulty() == Difficulty.MEDIUM));
            verify(structuredAiClient, times(1)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        @DisplayName("Final normal attempt may return a same-contract partial result")
        void generateQuestionsByTypeWithFallbacks_strategy1PartialSuccess_shouldReturnPartialResults() throws Exception {
            // Given - Strategy 1 returns partial results on last attempt (2 questions instead of 4)
            setupRateLimitConfig();
            setupLoggerStubbing();
            
            // Attempts 1-2 fail, attempt 3 returns partial results
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Generation failed")) // Attempt 1
                    .thenThrow(new AiServiceException("Generation failed")) // Attempt 2
                    .thenReturn(createStructuredResponse(2, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM)); // Attempt 3: partial success

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
        @DisplayName("Reduced-count fallback preserves requested type and difficulty")
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
                    .thenReturn(createStructuredResponse(2, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM)); // Strategy 2, attempt 1 (reduced count)

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
        @DisplayName("Normal retries share one provider dispatch budget")
        void generateQuestionsByTypeWithFallbacks_sharesBudgetAcrossNormalRetries() throws Exception {
            setupRateLimitConfig();
            when(rateLimitConfig.getMaxAttemptsPerTask()).thenReturn(2);
            List<StructuredQuestionRequest> requests = new ArrayList<>();

            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenAnswer(invocation -> {
                        StructuredQuestionRequest request = invocation.getArgument(0);
                        requests.add(request);
                        assertNotNull(request.getProviderAttemptBudget());
                        assertTrue(request.getProviderAttemptBudget().tryAcquire());
                        throw new AiServiceException("Simulated one-dispatch provider failure");
                    });

            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class,
                    Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE,
                    4, Difficulty.MEDIUM, 1, null, "en");

            assertTrue(result.isEmpty());
            assertEquals(2, requests.size());
            ProviderAttemptBudget sharedBudget = requests.get(0).getProviderAttemptBudget();
            assertSame(sharedBudget, requests.get(1).getProviderAttemptBudget());
            assertEquals(2, sharedBudget.consumedAttempts());
            assertTrue(sharedBudget.isExhausted());
            verify(structuredAiClient, times(2)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        @DisplayName("Reduced-count fallback reuses the normal strategy provider dispatch budget")
        void generateQuestionsByTypeWithFallbacks_reusesBudgetForReducedCount() throws Exception {
            setupRateLimitConfig();
            when(rateLimitConfig.getMaxAttemptsPerTask()).thenReturn(4);
            List<StructuredQuestionRequest> requests = new ArrayList<>();

            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenAnswer(invocation -> {
                        StructuredQuestionRequest request = invocation.getArgument(0);
                        requests.add(request);
                        assertNotNull(request.getProviderAttemptBudget());
                        assertTrue(request.getProviderAttemptBudget().tryAcquire());
                        if (requests.size() < 4) {
                            throw new AiServiceException("Simulated one-dispatch provider failure");
                        }
                        return createStructuredResponse(2, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM);
                    });

            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class,
                    Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE,
                    4, Difficulty.MEDIUM, 1, null, "en");

            assertEquals(2, result.size());
            assertEquals(List.of(4, 4, 4, 2), requests.stream()
                    .map(StructuredQuestionRequest::getQuestionCount)
                    .toList());
            ProviderAttemptBudget sharedBudget = requests.get(0).getProviderAttemptBudget();
            assertTrue(requests.stream()
                    .allMatch(request -> request.getProviderAttemptBudget() == sharedBudget));
            assertEquals(4, sharedBudget.consumedAttempts());
            verify(structuredAiClient, times(4)).generateQuestions(any(StructuredQuestionRequest.class));
        }

        @Test
        @DisplayName("Failed hotspot generation never requests an alternative type")
        void generateQuestionsByTypeWithFallbacks_doesNotSubstituteAlternativeType() throws Exception {
            // Given - all same-contract normal attempts fail
            setupRateLimitConfig();
            setupLoggerStubbing();

            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"));

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.HOTSPOT, 1, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            assertTrue(result.isEmpty());
            verify(structuredAiClient, times(3)).generateQuestions(argThat(request ->
                    request.getQuestionType() == QuestionType.HOTSPOT
                            && request.getDifficulty() == Difficulty.MEDIUM));
        }

        @Test
        @DisplayName("Failed hard generation never requests an easier difficulty")
        void generateQuestionsByTypeWithFallbacks_doesNotSubstituteDifficulty() throws Exception {
            setupRateLimitConfig();
            setupLoggerStubbing();
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"));

            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class,
                    Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE,
                    2, Difficulty.HARD, 1, UUID.randomUUID(), "en");

            assertTrue(result.isEmpty());
            verify(structuredAiClient, times(5)).generateQuestions(argThat(request ->
                    request.getQuestionType() == QuestionType.MCQ_SINGLE
                            && request.getDifficulty() == Difficulty.HARD));
        }

        @Test
        @DisplayName("Failed compliance generation never uses the MCQ last resort")
        void generateQuestionsByTypeWithFallbacks_doesNotUseLastResortType() throws Exception {
            setupRateLimitConfig();
            setupLoggerStubbing();
            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"));

            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class,
                    Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.COMPLIANCE,
                    1, Difficulty.MEDIUM, 1, UUID.randomUUID(), "en");

            assertTrue(result.isEmpty());
            verify(structuredAiClient, times(3)).generateQuestions(argThat(request ->
                    request.getQuestionType() == QuestionType.COMPLIANCE
                            && request.getDifficulty() == Difficulty.MEDIUM));
        }

        @Test
        @DisplayName("Single-question failure does not request easier difficulty or another type")
        void generateQuestionsByTypeWithFallbacks_singleQuestionCountDoesNotSubstitute() throws Exception {
            setupRateLimitConfig();
            setupLoggerStubbing();

            when(structuredAiClient.generateQuestions(any(StructuredQuestionRequest.class)))
                    .thenThrow(new AiServiceException("Failed"));

            // When
            Method method = AiQuizGenerationServiceImpl.class.getDeclaredMethod(
                    "generateQuestionsByTypeWithFallbacks", String.class, QuestionType.class, int.class, Difficulty.class, Integer.class, UUID.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Question> result = (List<Question>) method.invoke(
                    aiQuizGenerationService, testChunk.getContent(), QuestionType.MCQ_SINGLE, 1, Difficulty.HARD, 1, UUID.randomUUID(), "en");

            assertTrue(result.isEmpty());
            verify(structuredAiClient, times(3)).generateQuestions(argThat(request ->
                    request.getQuestionType() == QuestionType.MCQ_SINGLE
                            && request.getDifficulty() == Difficulty.HARD
                            && request.getQuestionCount() == 1));
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
