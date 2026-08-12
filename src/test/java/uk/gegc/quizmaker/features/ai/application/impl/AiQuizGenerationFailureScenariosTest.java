package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageService;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI Quiz Generation Failure Scenarios")
class AiQuizGenerationFailureScenariosTest {

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
    private UserRepository userRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AiRateLimitConfig rateLimitConfig;
    @Mock
    private InternalBillingService internalBillingService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private StructuredAiClient structuredAiClient;
    @Mock
    private QuestionContentValidationService questionContentValidationService;
    @Mock
    private ProviderUsageService providerUsageService;

    private AiQuizGenerationServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = spy(new AiQuizGenerationServiceImpl(
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
                new uk.gegc.quizmaker.features.question.application.QuestionContentShuffler(objectMapper),
                questionContentValidationService,
                providerUsageService
        ));
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    TransactionCallback<Object> callback = (TransactionCallback<Object>) invocation.getArgument(0);
                    org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
                    return callback.doInTransaction(mockStatus);
                });
        lenient().doAnswer(invocation -> {
                    Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
                    org.springframework.transaction.TransactionStatus mockStatus = mock(org.springframework.transaction.TransactionStatus.class);
                    callback.accept(mockStatus);
                    return null;
                })
                .when(transactionTemplate).executeWithoutResult(any());
        lenient().doReturn(10L).when(service).calculateBackoffDelay(anyInt());
        lenient().doNothing().when(service).sleepForRateLimit(anyLong());
        lenient().when(jobRepository.updateProcessedChunksAndStatus(any(), anyInt(), anyString()))
                .thenReturn(1);

        lenient().when(rateLimitConfig.getMaxRetries()).thenReturn(2);
    }

    @Test
    @DisplayName("Scenario 3.1: AI service unavailable triggers failure and reservation release")
    void aiServiceUnavailableShouldFailAndReleaseReservation() {
        Fixture fixture = prepareFixture();
        QuizGenerationJob job = fixture.job();
        GenerateQuizFromDocumentRequest request = fixture.request();
        UUID reservationId = fixture.reservationId();

        doThrow(new AiServiceException("OpenAI service unavailable"))
                .when(service)
                .generateQuestionsFromChunkWithJob(any(DocumentChunk.class), anyMap(), any(Difficulty.class), eq(job.getId()), anyString());

        assertThatThrownBy(() -> service.generateQuizFromDocumentAsync(job, request))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Failed to generate quiz");

        verify(internalBillingService).release(
                eq(reservationId),
                contains("Generation failed"),
                eq(job.getId().toString()),
                eq("quiz:" + job.getId() + ":release")
        );
        verify(eventPublisher, never()).publishEvent(any());

        ArgumentCaptor<QuizGenerationJob> jobCaptor = ArgumentCaptor.forClass(QuizGenerationJob.class);
        verify(jobRepository, atLeastOnce()).save(jobCaptor.capture());
        QuizGenerationJob finalState = jobCaptor.getAllValues().get(jobCaptor.getAllValues().size() - 1);
        assertThat(finalState.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(finalState.getBillingState()).isEqualTo(BillingState.RELEASED);
    }

    @Test
    @DisplayName("Terminal cancellation wins when a late generation worker fails")
    void terminalCancellationWinsWhenLateWorkerFails() {
        Fixture fixture = prepareFixture();
        QuizGenerationJob job = fixture.job();
        List<GenerationStatus> persistedStatuses = new ArrayList<>();
        when(jobRepository.save(any())).thenAnswer(invocation -> {
            QuizGenerationJob savedJob = invocation.getArgument(0);
            persistedStatuses.add(savedJob.getStatus());
            return savedJob;
        });
        doAnswer(invocation -> {
            job.setStatus(GenerationStatus.CANCELLED);
            throw new AiServiceException("late worker failure after cancellation");
        }).when(service).generateQuestionsFromChunkWithJob(
                any(DocumentChunk.class), anyMap(), any(Difficulty.class), eq(job.getId()), anyString());

        assertThatThrownBy(() -> service.generateQuizFromDocumentAsync(job, fixture.request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Failed to generate quiz");

        assertThat(job.getStatus()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(job.getBillingState()).isEqualTo(BillingState.RESERVED);
        assertThat(persistedStatuses).doesNotContain(GenerationStatus.FAILED);
        verify(internalBillingService, never()).release(any(), anyString(), anyString(), anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Scenario 3.2: rate limiting retries and fails cleanly when exhausted")
    void rateLimitingRetriesThenFails() {
        when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        
        // Phase 3: StructuredAiClient handles retries internally and throws after exhaustion
        when(structuredAiClient.generateQuestions(any()))
                .thenThrow(new AiServiceException("Rate limit exceeded after 3 attempts"));

        String chunkContent = "This chunk contains enough text to simulate realistic generation input." + "x".repeat(400);

        assertThatThrownBy(() -> service.generateQuestionsByType(chunkContent, QuestionType.MCQ_SINGLE, 1, Difficulty.MEDIUM))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Failed to generate questions");

        // Phase 3: Rate limit retries handled by StructuredAiClient (not service)
        // Service no longer calls sleepForRateLimit directly - that's in StructuredAiClient
        clearInvocations(service);
        reset(structuredAiClient);

        Fixture fixture = prepareFixture();
        QuizGenerationJob job = fixture.job();
        GenerateQuizFromDocumentRequest request = fixture.request();
        UUID reservationId = fixture.reservationId();

        doThrow(new AiServiceException("Rate limit exceeded after retries"))
                .when(service)
                .generateQuestionsFromChunkWithJob(any(DocumentChunk.class), anyMap(), any(Difficulty.class), eq(job.getId()), anyString());

        assertThatThrownBy(() -> service.generateQuizFromDocumentAsync(job, request))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Failed to generate quiz");

        verify(internalBillingService).release(
                eq(reservationId),
                contains("Generation failed"),
                eq(job.getId().toString()),
                eq("quiz:" + job.getId() + ":release")
        );
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Scenario 3.3: parsing failures bubble up and release reservation")
    void parsingFailurePropagatesAndReleasesReservation() {
        Fixture fixture = prepareFixture();
        QuizGenerationJob job = fixture.job();
        GenerateQuizFromDocumentRequest request = fixture.request();
        UUID reservationId = fixture.reservationId();

        when(structuredAiClient.generateQuestions(any()))
                .thenThrow(new AiServiceException(
                        "Failed to parse AI response after retries",
                        new AIResponseParseException("invalid json")
                ));

        assertThatThrownBy(() -> service.generateQuizFromDocumentAsync(job, request))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Failed to generate quiz");

        verify(internalBillingService).release(
                eq(reservationId),
                contains("Generation failed"),
                eq(job.getId().toString()),
                eq("quiz:" + job.getId() + ":release")
        );
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Scenario 3.4: empty AI response marks job failed and releases tokens")
    void emptyResponseTriggersFailure() {
        Fixture fixture = prepareFixture();
        QuizGenerationJob job = fixture.job();
        GenerateQuizFromDocumentRequest request = fixture.request();
        UUID reservationId = fixture.reservationId();

        when(structuredAiClient.generateQuestions(any())).thenReturn(null);

        assertThatThrownBy(() -> service.generateQuizFromDocumentAsync(job, request))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Failed to generate quiz");

        verify(internalBillingService).release(
                eq(reservationId),
                contains("Generation failed"),
                eq(job.getId().toString()),
                eq("quiz:" + job.getId() + ":release")
        );
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Two-chunk coverage uses the full target and exact eighty percent releases the reservation")
    void exactThresholdCoverageFailsAndReleasesReservation() {
        Fixture fixture = prepareFixture(5, 2);
        fixture.document().getChunks().forEach(chunk -> chunk.setContent("x".repeat(150)));
        List<Question> generated = questions(4, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM);
        doReturn(CompletableFuture.completedFuture(generated))
                .when(service)
                .generateQuestionsFromChunkWithJob(
                        any(DocumentChunk.class), anyMap(), eq(Difficulty.MEDIUM),
                        eq(fixture.job().getId()), eq("en"));

        assertThatThrownBy(() -> service.generateQuizFromDocumentAsync(fixture.job(), fixture.request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("8/10")
                .hasMessageContaining("does not exceed the required 80% threshold");

        verify(internalBillingService).release(
                eq(fixture.reservationId()),
                contains("Generation failed"),
                eq(fixture.job().getId().toString()),
                eq("quiz:" + fixture.job().getId() + ":release")
        );
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(fixture.job().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(fixture.job().getBillingState()).isEqualTo(BillingState.RELEASED);
    }

    @Test
    @DisplayName("Coverage above eighty percent publishes only accepted questions")
    void aboveThresholdCoveragePublishesAcceptedQuestions() {
        Fixture fixture = prepareFixture(10);
        fixture.document().getChunks().get(0).setContent("x".repeat(150));
        List<Question> generated = questions(9, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM);
        doReturn(CompletableFuture.completedFuture(generated))
                .when(service)
                .generateQuestionsFromChunkWithJob(
                        any(DocumentChunk.class), anyMap(), eq(Difficulty.MEDIUM),
                        eq(fixture.job().getId()), eq("en"));

        service.generateQuizFromDocumentAsync(fixture.job(), fixture.request());

        ArgumentCaptor<QuizGenerationCompletedEvent> eventCaptor =
                ArgumentCaptor.forClass(QuizGenerationCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getAllQuestions()).hasSize(9);
        assertThat(eventCaptor.getValue().getChunkQuestions()).containsOnlyKeys(0);
        assertThat(eventCaptor.getValue().getOriginalRequest()).isSameAs(fixture.request());
        verify(internalBillingService, never()).release(
                eq(fixture.reservationId()), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Exact full coverage preserves the existing successful completion path")
    void exactCoveragePreservesSuccessfulCompletion() {
        Fixture fixture = prepareFixture(3);
        List<Question> generated = questions(3, QuestionType.MCQ_SINGLE, Difficulty.MEDIUM);
        doReturn(CompletableFuture.completedFuture(generated))
                .when(service)
                .generateQuestionsFromChunkWithJob(
                        any(DocumentChunk.class), anyMap(), eq(Difficulty.MEDIUM),
                        eq(fixture.job().getId()), eq("en"));

        service.generateQuizFromDocumentAsync(fixture.job(), fixture.request());

        ArgumentCaptor<QuizGenerationCompletedEvent> eventCaptor =
                ArgumentCaptor.forClass(QuizGenerationCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getAllQuestions()).hasSize(3);
        assertThat(eventCaptor.getValue().getChunkQuestions().get(0)).containsExactlyElementsOf(generated);
        verify(internalBillingService, never()).release(
                eq(fixture.reservationId()), anyString(), anyString(), anyString());
    }

    private Fixture prepareFixture() {
        return prepareFixture(2);
    }

    private Fixture prepareFixture(int requestedQuestionCount) {
        return prepareFixture(requestedQuestionCount, 1);
    }

    private Fixture prepareFixture(int requestedQuestionCount, int chunkCount) {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("test-user");
        user.setEmail("test-user@example.com");
        user.setActive(true);

        QuizGenerationJob job = new QuizGenerationJob();
        job.setId(jobId);
        job.setUser(user);
        job.setDocumentId(documentId);
        job.setStatus(GenerationStatus.PENDING);
        job.setBillingReservationId(reservationId);
        job.setBillingState(BillingState.RESERVED);
        job.setBillingEstimatedTokens(200L);
        job.setReservationExpiresAt(LocalDateTime.now().plusMinutes(15));

        Document document = new Document();
        document.setId(documentId);
        document.setUploadedBy(user);
        document.setStatus(Document.DocumentStatus.PROCESSED);
        document.setOriginalFilename("sample.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(1024L);
        document.setFilePath("/tmp/sample.pdf");
        document.setUploadedAt(LocalDateTime.now());
        document.setProcessedAt(LocalDateTime.now());
        document.setChunks(new ArrayList<>());

        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(UUID.randomUUID());
            chunk.setChunkIndex(chunkIndex);
            chunk.setTitle("Chunk " + chunkIndex);
            chunk.setContent("This chunk contains enough content to allow generation attempts while testing failure paths. "
                    + "x".repeat(600));
            chunk.setStartPage(chunkIndex + 1);
            chunk.setEndPage(chunkIndex + 1);
            chunk.setWordCount(800);
            chunk.setCharacterCount(700);
            chunk.setCreatedAt(LocalDateTime.now());
            chunk.setChunkType(DocumentChunk.ChunkType.SECTION);
            document.getChunks().add(chunk);
        }

        Map<QuestionType, Integer> questionsPerType = Map.of(
                QuestionType.MCQ_SINGLE, requestedQuestionCount);

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Failure Scenario Quiz",
                "Exercise failure handling",
                questionsPerType,
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );

        when(jobRepository.findAll()).thenReturn(List.of(job));
        when(jobRepository.findById(eq(jobId))).thenAnswer(invocation -> Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(invocation -> (QuizGenerationJob) invocation.getArgument(0));
        when(documentRepository.findByIdWithChunksAndUser(eq(documentId))).thenReturn(Optional.of(document));
        when(internalBillingService.renewReservationLease(user.getId(), reservationId, jobId))
                .thenReturn(new ReservationDto(
                        reservationId,
                        user.getId(),
                        ReservationState.ACTIVE,
                        200L,
                        0L,
                        job.getReservationExpiresAt(),
                        jobId,
                        LocalDateTime.now(),
                        LocalDateTime.now()
                ));
        when(internalBillingService.release(eq(reservationId), anyString(), eq(jobId.toString()), anyString()))
                .thenReturn(new ReleaseResultDto(reservationId, 200L));

        return new Fixture(job, request, reservationId, document);
    }

    private List<Question> questions(int count, QuestionType type, Difficulty difficulty) {
        List<Question> questions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Question question = new Question();
            question.setType(type);
            question.setDifficulty(difficulty);
            question.setQuestionText("Question " + index);
            question.setContent(switch (type) {
                case MCQ_SINGLE -> """
                        {"options":[
                          {"id":"a","text":"Correct","correct":true},
                          {"id":"b","text":"Distractor","correct":false}
                        ]}
                        """;
                case TRUE_FALSE -> "{\"answer\":true}";
                default -> throw new IllegalArgumentException("No valid test fixture for " + type);
            });
            questions.add(question);
        }
        return questions;
    }

    private record Fixture(
            QuizGenerationJob job,
            GenerateQuizFromDocumentRequest request,
            UUID reservationId,
            Document document
    ) {
    }
}
