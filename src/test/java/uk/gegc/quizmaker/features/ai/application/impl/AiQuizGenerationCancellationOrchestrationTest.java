package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageException;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCoverageReconciledEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;
import uk.gegc.quizmaker.shared.testing.DirectAiProviderTaskScheduler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI quiz generation cancellation orchestration")
class AiQuizGenerationCancellationOrchestrationTest {

    @Mock private ChatClient chatClient;
    @Mock private DocumentRepository documentRepository;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private QuestionResponseParser questionResponseParser;
    @Mock private QuizGenerationJobRepository jobRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private InternalBillingService internalBillingService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private StructuredAiClient structuredAiClient;
    @Mock private QuestionContentValidationService questionContentValidationService;
    @Mock private ProviderUsageService providerUsageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiRateLimitConfig rateLimitConfig = new AiRateLimitConfig();
    private AiQuizGenerationServiceImpl service;

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
                new QuestionContentShuffler(objectMapper),
                questionContentValidationService,
                providerUsageService,
                DirectAiProviderTaskScheduler.INSTANCE
        ));

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(jobRepository.incrementCompletedTasks(any(), anyInt(), anyString())).thenReturn(1);
        lenient().when(jobRepository.updateProcessedChunksAndStatus(any(), anyInt(), anyString())).thenReturn(1);
    }

    @Test
    @DisplayName("Cancelled job waiting for its worker never returns to processing")
    void cancelledBeforeWorkerStartReturnsWithoutGeneration() {
        Fixture fixture = fixture(Map.of(QuestionType.MCQ_SINGLE, 2));
        fixture.job().setStatus(GenerationStatus.CANCELLED);
        fixture.job().setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
        fixture.job().setCurrentChunk("Cancelled by user");

        assertThatCode(() -> service.generateQuizFromDocumentAsync(
                fixture.job().getId(), fixture.request()))
                .doesNotThrowAnyException();

        assertThat(fixture.job().getStatus()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(fixture.job().getCurrentChunk()).isEqualTo("Cancelled by user");
        assertThat(service.getProgress(fixture.job().getId())).isNull();
        verify(jobRepository, never()).save(any());
        verifyNoInteractions(documentRepository, structuredAiClient, eventPublisher, internalBillingService);
    }

    @Test
    @DisplayName("Cancellation returned from provider backoff exits every remaining fallback and type")
    void cancellationFromStructuredClientStopsOuterOrchestration() {
        Map<QuestionType, Integer> requested = new LinkedHashMap<>();
        requested.put(QuestionType.MCQ_SINGLE, 2);
        requested.put(QuestionType.FILL_GAP, 2);
        requested.put(QuestionType.TRUE_FALSE, 2);
        Fixture fixture = fixture(requested);
        AtomicInteger providerCalls = new AtomicInteger();

        when(structuredAiClient.generateQuestions(any())).thenAnswer(invocation -> {
            if (providerCalls.incrementAndGet() == 1) {
                return StructuredQuestionResponse.builder()
                        .questions(List.of(
                                structuredQuestion("Generated before cancellation 1"),
                                structuredQuestion("Generated before cancellation 2")
                        ))
                        .warnings(List.of())
                        .tokensUsed(20L)
                        .build();
            }
            fixture.job().setStatus(GenerationStatus.CANCELLED);
            fixture.job().setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
            fixture.job().setBillingState(BillingState.RELEASED);
            fixture.job().setCurrentChunk("Cancelled by user");
            return StructuredQuestionResponse.builder()
                    .questions(List.of())
                    .warnings(List.of("Generation cancelled by user"))
                    .tokensUsed(0L)
                    .build();
        });

        assertThatCode(() -> service.generateQuizFromDocumentAsync(
                fixture.job(), fixture.request()))
                .doesNotThrowAnyException();

        verify(structuredAiClient, times(2)).generateQuestions(any());
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(internalBillingService, never()).release(any(), anyString(), anyString(), anyString());
        verify(jobRepository).incrementCompletedTasks(
                fixture.job().getId(), 1, "Chunk 0 · MCQ_SINGLE · done");
        assertThat(fixture.job().getStatus()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(fixture.job().getBillingState()).isEqualTo(BillingState.RELEASED);
        assertThat(fixture.job().getCurrentChunk()).isEqualTo("Cancelled by user");
        assertThat(service.getProgress(fixture.job().getId())).isNull();
    }

    @Test
    @DisplayName("Cancellation winning synchronous coverage persistence returns without completion or failure")
    void cancelledFirstCoverageRaceReturnsCleanly() {
        Fixture fixture = fixture(Map.of(QuestionType.MCQ_SINGLE, 2));
        doReturn(CompletableFuture.completedFuture(questions(2)))
                .when(service)
                .generateQuestionsFromChunkWithJob(
                        any(DocumentChunk.class), any(), any(Difficulty.class),
                        any(UUID.class), anyString());
        doAnswer(invocation -> {
            fixture.job().setStatus(GenerationStatus.CANCELLED);
            fixture.job().setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
            fixture.job().setBillingState(BillingState.RELEASED);
            fixture.job().setCurrentChunk("Cancelled by user");
            throw new QuizGenerationCoverageException(
                    "Generation job is no longer eligible for coverage reconciliation");
        }).when(eventPublisher).publishEvent(isA(QuizGenerationCoverageReconciledEvent.class));

        assertThatCode(() -> service.generateQuizFromDocumentAsync(
                fixture.job(), fixture.request()))
                .doesNotThrowAnyException();

        verify(eventPublisher).publishEvent(isA(QuizGenerationCoverageReconciledEvent.class));
        verify(eventPublisher, never()).publishEvent(isA(QuizGenerationCompletedEvent.class));
        verify(internalBillingService, never()).release(any(), anyString(), anyString(), anyString());
        assertThat(fixture.job().getStatus()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(fixture.job().getCurrentChunk()).isEqualTo("Cancelled by user");
        assertThat(service.getProgress(fixture.job().getId())).isNull();
    }

    @Test
    @DisplayName("Genuine coverage persistence failure still fails the job and releases its reservation")
    void genuineCoverageFailureRetainsFailurePath() {
        Fixture fixture = fixture(Map.of(QuestionType.MCQ_SINGLE, 2));
        doReturn(CompletableFuture.completedFuture(questions(2)))
                .when(service)
                .generateQuestionsFromChunkWithJob(
                        any(DocumentChunk.class), any(), any(Difficulty.class),
                        any(UUID.class), anyString());
        doAnswer(invocation -> {
            throw new QuizGenerationCoverageException("Coverage persistence unavailable");
        }).when(eventPublisher).publishEvent(isA(QuizGenerationCoverageReconciledEvent.class));

        assertThatThrownBy(() -> service.generateQuizFromDocumentAsync(
                fixture.job(), fixture.request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Coverage persistence unavailable");

        assertThat(fixture.job().getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(fixture.job().getBillingState()).isEqualTo(BillingState.RELEASED);
        verify(internalBillingService).release(
                fixture.job().getBillingReservationId(),
                "Generation failed: Coverage persistence unavailable",
                fixture.job().getId().toString(),
                "quiz:" + fixture.job().getId() + ":release"
        );
        verify(eventPublisher, never()).publishEvent(isA(QuizGenerationCompletedEvent.class));
    }

    @Test
    @DisplayName("Terminal job progress remains unchanged when a stale worker reports progress")
    void terminalJobRejectsStaleProgressMutation() {
        Fixture fixture = fixture(Map.of(QuestionType.MCQ_SINGLE, 2));
        fixture.job().setStatus(GenerationStatus.CANCELLED);
        fixture.job().setProcessedChunks(1);
        fixture.job().setCurrentChunk("Cancelled by user");

        service.updateJobProgress(fixture.job().getId(), 5, "Generation completed");

        assertThat(fixture.job().getProcessedChunks()).isEqualTo(1);
        assertThat(fixture.job().getCurrentChunk()).isEqualTo("Cancelled by user");
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Direct generation without a job keeps its successful legacy contract")
    void directGenerationWithoutJobRemainsCompatible() {
        when(structuredAiClient.generateQuestions(any())).thenReturn(
                StructuredQuestionResponse.builder()
                        .questions(List.of(StructuredQuestion.builder()
                                .questionText("Water freezes at zero degrees Celsius.")
                                .type(QuestionType.TRUE_FALSE)
                                .difficulty(Difficulty.MEDIUM)
                                .content("{\"answer\":true}")
                                .hint("Think about water.")
                                .explanation("This is the standard freezing point.")
                                .confidence(0.99)
                                .build()))
                        .warnings(List.of())
                        .build());

        List<Question> result = service.generateQuestionsByType(
                "Water freezes at zero degrees Celsius under standard atmospheric pressure.",
                QuestionType.TRUE_FALSE,
                1,
                Difficulty.MEDIUM
        );

        assertThat(result).singleElement().satisfies(question -> {
            assertThat(question.getType()).isEqualTo(QuestionType.TRUE_FALSE);
            assertThat(question.getContent()).contains("\"answer\":true");
        });
        verify(structuredAiClient).generateQuestions(any());
        verifyNoInteractions(jobRepository, internalBillingService, eventPublisher);
    }

    @Test
    @DisplayName("Cancellation outcome cancels every incomplete sibling chunk future")
    void cancellationOutcomeCancelsIncompleteSiblingChunkFutures() throws Exception {
        Fixture fixture = fixture(Map.of(QuestionType.MCQ_SINGLE, 2));
        DocumentChunk secondChunk = new DocumentChunk();
        secondChunk.setId(UUID.randomUUID());
        secondChunk.setChunkIndex(1);
        secondChunk.setContent("A second sufficiently detailed chunk for queued cancellation testing.");
        fixture.document().getChunks().add(secondChunk);

        CompletableFuture<List<Question>> cancelledChunk = new CompletableFuture<>();
        CompletableFuture<List<Question>> queuedSibling = new CompletableFuture<>();
        AtomicInteger submissions = new AtomicInteger();
        CountDownLatch bothChunksScheduled = new CountDownLatch(2);
        doAnswer(invocation -> {
            CompletableFuture<List<Question>> result = submissions.getAndIncrement() == 0
                    ? cancelledChunk
                    : queuedSibling;
            bothChunksScheduled.countDown();
            return result;
        }).when(service).generateQuestionsFromChunkWithJob(
                any(DocumentChunk.class), any(), any(Difficulty.class),
                any(UUID.class), anyString());

        CompletableFuture<Void> orchestration = CompletableFuture.runAsync(
                () -> service.generateQuizFromDocumentAsync(fixture.job(), fixture.request()));
        assertThat(bothChunksScheduled.await(1, TimeUnit.SECONDS)).isTrue();

        fixture.job().setStatus(GenerationStatus.CANCELLED);
        fixture.job().setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
        fixture.job().setBillingState(BillingState.RELEASED);
        cancelledChunk.completeExceptionally(new QuizGenerationCancelledException());

        orchestration.get(1, TimeUnit.SECONDS);

        assertThat(cancelledChunk).isCompletedExceptionally().isNotCancelled();
        assertThat(queuedSibling).isCancelled();
        assertThat(service.getProgress(fixture.job().getId())).isNull();
        verify(service, times(2)).generateQuestionsFromChunkWithJob(
                any(DocumentChunk.class), any(), any(Difficulty.class),
                any(UUID.class), anyString());
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(internalBillingService, never()).release(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Later chunk cancellation cleans blocked earlier work without submission-order delay")
    void laterChunkCancellationCleansBlockedEarlierWorkPromptly() throws Exception {
        Fixture fixture = fixture(Map.of(QuestionType.MCQ_SINGLE, 2));
        DocumentChunk secondChunk = new DocumentChunk();
        secondChunk.setId(UUID.randomUUID());
        secondChunk.setChunkIndex(1);
        secondChunk.setContent("A second sufficiently detailed chunk that reports persisted cancellation.");
        DocumentChunk completedThirdChunk = new DocumentChunk();
        completedThirdChunk.setId(UUID.randomUUID());
        completedThirdChunk.setChunkIndex(2);
        completedThirdChunk.setContent("A third sufficiently detailed chunk that completed before cancellation.");
        fixture.document().getChunks().add(secondChunk);
        fixture.document().getChunks().add(completedThirdChunk);

        CompletableFuture<List<Question>> blockedFirst = new CompletableFuture<>();
        CompletableFuture<List<Question>> cancelledSecond = new CompletableFuture<>();
        List<Question> completedQuestions = questions(2);
        CompletableFuture<List<Question>> completedThird = CompletableFuture.completedFuture(completedQuestions);
        List<CompletableFuture<List<Question>>> scheduled = List.of(
                blockedFirst, cancelledSecond, completedThird);
        AtomicInteger submissions = new AtomicInteger();
        CountDownLatch allChunksScheduled = new CountDownLatch(3);
        CountDownLatch blockedFirstCancelled = new CountDownLatch(1);
        blockedFirst.whenComplete((ignored, failure) -> {
            if (blockedFirst.isCancelled()) {
                blockedFirstCancelled.countDown();
            }
        });
        doAnswer(invocation -> {
            CompletableFuture<List<Question>> result = scheduled.get(submissions.getAndIncrement());
            allChunksScheduled.countDown();
            return result;
        }).when(service).generateQuestionsFromChunkWithJob(
                any(DocumentChunk.class), any(), any(Difficulty.class),
                any(UUID.class), anyString());

        CompletableFuture<Void> orchestration = CompletableFuture.runAsync(
                () -> service.generateQuizFromDocumentAsync(fixture.job(), fixture.request()));
        assertThat(allChunksScheduled.await(1, TimeUnit.SECONDS)).isTrue();

        fixture.job().setStatus(GenerationStatus.CANCELLED);
        fixture.job().setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
        fixture.job().setBillingState(BillingState.RELEASED);
        assertThat(cancelledSecond.cancel(false)).isTrue();

        assertThat(blockedFirstCancelled.await(1, TimeUnit.SECONDS)).isTrue();
        orchestration.get(1, TimeUnit.SECONDS);

        assertThat(blockedFirst).isCancelled();
        assertThat(cancelledSecond).isCancelled();
        assertThat(completedThird.isCancelled()).isFalse();
        assertThat(completedThird.join()).isSameAs(completedQuestions);
        assertThat(service.getProgress(fixture.job().getId())).isNull();
        verify(service, times(3)).generateQuestionsFromChunkWithJob(
                any(DocumentChunk.class), any(), any(Difficulty.class),
                any(UUID.class), anyString());
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(internalBillingService, never()).release(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Generic future cancellation does not fan out before persisted job cancellation")
    void genericFutureCancellationRequiresPersistedJobCancellation() throws Exception {
        Fixture fixture = fixture(Map.of(QuestionType.MCQ_SINGLE, 2));
        DocumentChunk secondChunk = new DocumentChunk();
        secondChunk.setId(UUID.randomUUID());
        secondChunk.setChunkIndex(1);
        secondChunk.setContent("A second sufficiently detailed chunk for cancellation authority testing.");
        fixture.document().getChunks().add(secondChunk);

        CompletableFuture<List<Question>> blockedFirst = new CompletableFuture<>();
        CompletableFuture<List<Question>> cancelledSecond = new CompletableFuture<>();
        AtomicInteger submissions = new AtomicInteger();
        CountDownLatch bothChunksScheduled = new CountDownLatch(2);
        doAnswer(invocation -> {
            CompletableFuture<List<Question>> result = submissions.getAndIncrement() == 0
                    ? blockedFirst
                    : cancelledSecond;
            bothChunksScheduled.countDown();
            return result;
        }).when(service).generateQuestionsFromChunkWithJob(
                any(DocumentChunk.class), any(), any(Difficulty.class),
                any(UUID.class), anyString());

        CompletableFuture<Void> orchestration = CompletableFuture.runAsync(
                () -> service.generateQuizFromDocumentAsync(fixture.job(), fixture.request()));
        assertThat(bothChunksScheduled.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.job().getStatus()).isEqualTo(GenerationStatus.PROCESSING);

        assertThat(cancelledSecond.cancel(false)).isTrue();

        assertThat(blockedFirst).isNotDone();

        fixture.job().setStatus(GenerationStatus.CANCELLED);
        fixture.job().setFinalizationState(QuizGenerationFinalizationState.CANCELLED);
        fixture.job().setBillingState(BillingState.RELEASED);
        blockedFirst.completeExceptionally(new QuizGenerationCancelledException());
        orchestration.get(1, TimeUnit.SECONDS);

        assertThat(service.getProgress(fixture.job().getId())).isNull();
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(internalBillingService, never()).release(any(), anyString(), anyString(), anyString());
    }

    private Fixture fixture(Map<QuestionType, Integer> questionsPerType) {
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

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setChunkIndex(0);
        chunk.setTitle("Chunk 0");
        chunk.setContent("A sufficiently long document chunk for deterministic cancellation testing. "
                + "x".repeat(600));
        chunk.setStartPage(1);
        chunk.setEndPage(1);
        chunk.setWordCount(100);
        chunk.setCharacterCount(700);
        chunk.setCreatedAt(LocalDateTime.now());
        chunk.setChunkType(DocumentChunk.ChunkType.SECTION);

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
        document.setChunks(new ArrayList<>(List.of(chunk)));

        GenerateQuizFromDocumentRequest request = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Cancellation Quiz",
                "Verify clean cancellation",
                questionsPerType,
                Difficulty.MEDIUM,
                2,
                null,
                List.of(),
                "en"
        );

        lenient().when(jobRepository.findById(jobId)).thenAnswer(invocation -> Optional.of(job));
        lenient().when(documentRepository.findByIdWithChunksAndUser(documentId))
                .thenReturn(Optional.of(document));
        lenient().when(internalBillingService.renewReservationLease(
                        user.getId(), reservationId, jobId))
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
        lenient().when(internalBillingService.release(
                        eq(reservationId), anyString(), anyString(), anyString()))
                .thenReturn(new ReleaseResultDto(reservationId, 200L));

        return new Fixture(job, document, request);
    }

    private StructuredQuestion structuredQuestion(String questionText) {
        return StructuredQuestion.builder()
                .questionText(questionText)
                .type(QuestionType.MCQ_SINGLE)
                .difficulty(Difficulty.MEDIUM)
                .content("""
                        {"options":[
                          {"id":"correct","text":"Correct","correct":true},
                          {"id":"distractor","text":"Distractor","correct":false}
                        ]}
                        """)
                .build();
    }

    private List<Question> questions(int count) {
        List<Question> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Question question = new Question();
            question.setQuestionText("Question " + index);
            question.setType(QuestionType.MCQ_SINGLE);
            question.setDifficulty(Difficulty.MEDIUM);
            question.setContent("""
                    {"options":[
                      {"id":"a","text":"Correct","correct":true},
                      {"id":"b","text":"Distractor","correct":false}
                    ]}
                    """);
            result.add(question);
        }
        return result;
    }

    private record Fixture(
            QuizGenerationJob job,
            Document document,
            GenerateQuizFromDocumentRequest request
    ) {
    }
}
