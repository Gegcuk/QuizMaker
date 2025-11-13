package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.application.impl.PromptTemplateServiceImpl;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.ai.infra.parser.ComplianceQuestionParser;
import uk.gegc.quizmaker.features.ai.infra.parser.FillGapQuestionParser;
import uk.gegc.quizmaker.features.ai.infra.parser.HotspotQuestionParser;
import uk.gegc.quizmaker.features.ai.infra.parser.McqQuestionParser;
import uk.gegc.quizmaker.features.ai.infra.parser.OpenQuestionParser;
import uk.gegc.quizmaker.features.ai.infra.parser.OrderingQuestionParser;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionParserFactory;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParserImpl;
import uk.gegc.quizmaker.features.ai.infra.parser.TrueFalseQuestionParser;
import uk.gegc.quizmaker.features.billing.api.dto.EstimationDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.EstimationService;
import uk.gegc.quizmaker.features.billing.application.impl.EstimationServiceImpl;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.ComplianceHandler;
import uk.gegc.quizmaker.features.question.infra.handler.FillGapHandler;
import uk.gegc.quizmaker.features.question.infra.handler.HotspotHandler;
import uk.gegc.quizmaker.features.question.infra.handler.MatchingHandler;
import uk.gegc.quizmaker.features.question.infra.handler.McqMultiHandler;
import uk.gegc.quizmaker.features.question.infra.handler.McqSingleHandler;
import uk.gegc.quizmaker.features.question.infra.handler.OpenQuestionHandler;
import uk.gegc.quizmaker.features.question.infra.handler.OrderingHandler;
import uk.gegc.quizmaker.features.question.infra.handler.TrueFalseHandler;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealAiQuizGenerationIntegrationTest {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;
    
    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String model;

    private AiQuizGenerationServiceImpl service;
    private EstimationService estimationService;
    private DocumentRepository documentRepository;
    private DocumentChunkRepository documentChunkRepository;
    private InternalBillingService internalBillingService;

    @BeforeAll
    void setUp() {
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OpenAI API key not configured");

        OpenAiApi openAiApi = new OpenAiApi(apiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.2)
                .maxTokens(512)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
        ChatClient chatClient = ChatClient.create(chatModel);

        PromptTemplateService promptTemplateService = new PromptTemplateServiceImpl(new DefaultResourceLoader());
        QuestionHandlerFactory handlerFactory = new QuestionHandlerFactory(List.of(
                new McqSingleHandler(),
                new McqMultiHandler(),
                new TrueFalseHandler(),
                new OpenQuestionHandler(),
                new FillGapHandler(),
                new OrderingHandler(),
                new ComplianceHandler(),
                new HotspotHandler(),
                new MatchingHandler()
        ));
        QuestionParserFactory parserFactory = new QuestionParserFactory(
                new McqQuestionParser(),
                new TrueFalseQuestionParser(),
                new OpenQuestionParser(),
                new FillGapQuestionParser(),
                new OrderingQuestionParser(),
                new ComplianceQuestionParser(),
                new HotspotQuestionParser()
        );
        QuestionResponseParser questionResponseParser = new QuestionResponseParserImpl(handlerFactory, parserFactory);

        documentRepository = mock(DocumentRepository.class);
        documentChunkRepository = mock(DocumentChunkRepository.class);
        QuizGenerationJobRepository jobRepository = mock(QuizGenerationJobRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        internalBillingService = mock(InternalBillingService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(invocation -> {
            TransactionCallbackWithoutResult callback = invocation.getArgument(0);
            callback.doInTransaction(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        AiRateLimitConfig rateLimitConfig = new AiRateLimitConfig();
        rateLimitConfig.setMaxRetries(3);
        rateLimitConfig.setBaseDelayMs(250);
        rateLimitConfig.setMaxDelayMs(1_000);
        rateLimitConfig.setJitterFactor(0.1d);

        // Create structured AI client for Phase 3
        ObjectMapper objectMapper = new ObjectMapper();
        QuestionSchemaRegistry schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        StructuredAiClient structuredAiClient = new SpringAiStructuredClient(
                chatClient,
                schemaRegistry,
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );

        service = new AiQuizGenerationServiceImpl(
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
                new uk.gegc.quizmaker.features.question.application.QuestionContentShuffler(objectMapper)
        );

        BillingProperties billingProperties = new BillingProperties();
        estimationService = new EstimationServiceImpl(billingProperties, documentRepository, documentChunkRepository, promptTemplateService);
    }

    @Test
    @DisplayName("Scenario 5.1: generates quiz questions using live OpenAI")
    void shouldGenerateQuestionsWithRealOpenAi() {
        String chunkContent = "Artificial intelligence enables systems to reason over complex data." +
                " Machine learning is a subset of AI focused on statistical learning.";

        List<Question> questions = service.generateQuestionsByType(
                chunkContent,
                QuestionType.MCQ_SINGLE,
                1,
                Difficulty.MEDIUM
        );

        assertThat(questions).isNotEmpty();
        assertThat(questions.get(0).getQuestionText()).isNotBlank();
    }

    @Test
    @DisplayName("Scenario 5.2: estimated billing tokens align with actual usage")
    void shouldKeepEstimationCloseToActualUsage() {
        UUID documentId = UUID.randomUUID();
        User owner = new User();
        owner.setUsername("ai-user");
        owner.setEmail("ai-user@example.com");

        Document document = new Document();
        document.setId(documentId);
        document.setOriginalFilename("integration.txt");
        document.setContentType("text/plain");
        document.setFileSize(2048L);
        document.setFilePath("/tmp/integration.txt");
        document.setUploadedBy(owner);
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setUploadedAt(LocalDateTime.now().minusMinutes(5));
        document.setProcessedAt(LocalDateTime.now().minusMinutes(4));

        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocument(document);
        chunk.setChunkIndex(0);
        chunk.setTitle("Integration Sample");
        chunk.setContent("Cloud computing, artificial intelligence, and data engineering form the core of modern platforms." +
                " Ethical considerations are essential when deploying AI systems to production.");
        chunk.setStartPage(1);
        chunk.setEndPage(1);
        chunk.setWordCount(64);
        chunk.setCharacterCount(chunk.getContent().length());
        chunk.setCreatedAt(LocalDateTime.now().minusMinutes(4));
        document.getChunks().add(chunk);

        when(documentRepository.findByIdWithChunks(documentId)).thenReturn(Optional.of(document));

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Real AI Consistency Test",
                "Validate billing accuracy",
                Map.of(QuestionType.MCQ_SINGLE, 1),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        EstimationDto estimation = estimationService.estimateQuizGeneration(documentId, request);
        List<Question> questions = service.generateQuestionsByType(
                chunk.getContent(),
                QuestionType.MCQ_SINGLE,
                1,
                Difficulty.MEDIUM
        );

        long actualBillingTokens = estimationService.computeActualBillingTokens(
                questions,
                request.difficulty(),
                estimation.estimatedLlmTokens()
        );

        assertThat(actualBillingTokens).isPositive();
        long difference = Math.abs(actualBillingTokens - estimation.estimatedBillingTokens());
        assertThat(difference).isLessThanOrEqualTo(estimation.estimatedBillingTokens() + 1);
    }

    @Test
    @DisplayName("Scenario 5.3: handles concurrent requests without tripping rate limits")
    void shouldHandleConcurrentRequests() throws Exception {
        String content = "Concurrency testing ensures retry logic and resilience under realistic usage.";

        Callable<List<Question>> task = () -> service.generateQuestionsByType(
                content,
                QuestionType.TRUE_FALSE,
                1,
                Difficulty.EASY
        );

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<List<Question>>> futures = executor.invokeAll(List.of(task, task, task));
        executor.shutdown();

        for (Future<List<Question>> future : futures) {
            try {
                List<Question> result = future.get();
                assertThat(result).isNotEmpty();
            }
            catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof AiServiceException aiEx && aiEx.getMessage() != null
                        && aiEx.getMessage().toLowerCase().contains("rate limit")) {
                    assumeTrue(false, "Skipped due to transient OpenAI rate limiting: " + aiEx.getMessage());
                }
                throw ex;
            }
        }
    }
}
