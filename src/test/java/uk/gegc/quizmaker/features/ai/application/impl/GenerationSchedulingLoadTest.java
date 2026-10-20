package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.execution.ExecutorAiProviderTaskScheduler;
import uk.gegc.quizmaker.features.document.domain.model.*;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContentValidationServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.McqSingleHandler;
import uk.gegc.quizmaker.features.quiz.api.dto.*;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import uk.gegc.quizmaker.features.quiz.application.generation.impl.*;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.config.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline load through the real dispatcher, worker, orchestration and bounded provider scheduler. */
class GenerationSchedulingLoadTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 25})
    void completesEverySyntheticJobWithinFourByTwoExecutionSlots(int jobCount) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var metrics = new SimpleMeterRegistry();
        var config = new QuizJobProperties();
        var orchestration = (ThreadPoolTaskExecutor) new AsyncConfig().generationJobExecutor(config);
        var providers = new ThreadPoolTaskExecutor();
        providers.setCorePoolSize(8); providers.setMaxPoolSize(8); providers.setQueueCapacity(50);
        providers.setThreadNamePrefix("synthetic-provider-"); providers.initialize();
        var scheduler = new ExecutorAiProviderTaskScheduler(providers);
        var work = mock(GenerationWorkService.class);
        var repository = mock(QuizGenerationJobRepository.class);
        var documents = mock(DocumentRepository.class);
        var transactions = mock(TransactionTemplate.class);
        var events = mock(ApplicationEventPublisher.class);
        var provider = mock(StructuredAiClient.class);
        var usage = mock(ProviderUsageService.class);
        var jobs = new ConcurrentHashMap<UUID, QuizGenerationJob>();
        var sources = new ConcurrentHashMap<UUID, Document>();
        var claims = new ConcurrentLinkedQueue<GenerationWorkClaim>();
        var activeJobs = new AtomicInteger();
        var peakJobs = new AtomicInteger();
        var activeCalls = new AtomicInteger();
        var peakCalls = new AtomicInteger();
        var callsByJob = new ConcurrentHashMap<String, AtomicInteger>();
        var peaksByJob = new ConcurrentHashMap<String, AtomicInteger>();
        var firstWave = new CountDownLatch(2 * Math.min(4, jobCount));
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(jobCount);
        var completed = new ConcurrentLinkedQueue<QuizGenerationCompletedEvent>();
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        for (int n = 0; n < jobCount; n++) {
            var owner = new User(); owner.setId(UUID.randomUUID()); owner.setUsername("synthetic-" + n);
            var source = new Document(); source.setId(UUID.randomUUID()); source.setUploadedBy(owner);
            source.setStatus(Document.DocumentStatus.PROCESSED); source.setChunks(new ArrayList<>());
            for (int index = 0; index < 3; index++) {
                var chunk = new DocumentChunk(); chunk.setId(UUID.randomUUID()); chunk.setChunkIndex(index);
                chunk.setContent("Synthetic educational source for job " + n + " chunk " + index + ". ".repeat(60));
                chunk.setDocument(source); source.getChunks().add(chunk);
            }
            sources.put(source.getId(), source);
            var job = new QuizGenerationJob(); job.setId(UUID.randomUUID()); job.setUser(owner);
            job.setDocumentId(source.getId()); job.setStatus(GenerationStatus.PENDING);
            jobs.put(job.getId(), job);
            var request = new GenerateQuizFromDocumentRequest(source.getId(), QuizScope.ENTIRE_DOCUMENT,
                    null, null, null, "Synthetic quiz", null, Map.of(QuestionType.MCQ_SINGLE, 1),
                    Difficulty.MEDIUM, 2, null, List.of());
            claims.add(new GenerationWorkClaim(job.getId(), 1, source.getId(), mapper.writeValueAsString(request),
                    clock.instant().plusSeconds(300), 2));
        }
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(jobs.get(call.getArgument(0))));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(repository.updateProcessedChunksAndStatus(any(), anyInt(), anyString())).thenReturn(1);
        when(documents.findByIdWithChunksAndUser(any())).thenAnswer(call -> Optional.ofNullable(sources.get(call.getArgument(0))));
        when(transactions.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        doAnswer(call -> { ((java.util.function.Consumer<?>) call.getArgument(0)).accept(null); return null; })
                .when(transactions).executeWithoutResult(any());
        when(work.protect(any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(work.claimNext()).thenAnswer(call -> {
            var claim = claims.poll();
            if (claim != null) peakJobs.accumulateAndGet(activeJobs.incrementAndGet(), Math::max);
            return Optional.ofNullable(claim);
        });
        doAnswer(call -> { activeJobs.decrementAndGet(); done.countDown(); return null; }).when(work).finished(any());
        doAnswer(call -> { done.countDown(); return null; }).when(work).failed(any());
        doAnswer(call -> {
            if (call.getArgument(0) instanceof QuizGenerationCompletedEvent event) completed.add(event);
            return null;
        }).when(events).publishEvent(any(org.springframework.context.ApplicationEvent.class));
        when(provider.generateQuestions(any())).thenAnswer(call -> {
            StructuredQuestionRequest request = call.getArgument(0);
            String id = request.getMetadata().get("jobId").toString();
            var current = callsByJob.computeIfAbsent(id, ignored -> new AtomicInteger());
            peaksByJob.computeIfAbsent(id, ignored -> new AtomicInteger()).accumulateAndGet(current.incrementAndGet(), Math::max);
            peakCalls.accumulateAndGet(activeCalls.incrementAndGet(), Math::max);
            firstWave.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return StructuredQuestionResponse.builder().tokensUsed(1L).warnings(List.of())
                        .questions(List.of(StructuredQuestion.builder().type(QuestionType.MCQ_SINGLE)
                                .difficulty(Difficulty.MEDIUM).confidence(1.0).questionText(request.getChunkContent())
                                .content("{\"options\":[{\"id\":\"a\",\"text\":\"Yes\",\"correct\":true},{\"id\":\"b\",\"text\":\"No\",\"correct\":false}]}")
                                .build())).build();
            } finally { activeCalls.decrementAndGet(); current.decrementAndGet(); }
        });
        var ai = new AiQuizGenerationServiceImpl(null, documents, null, null, repository, null, mapper, events,
                new AiRateLimitConfig(), null, transactions, provider, new QuestionContentShuffler(mapper),
                new QuestionContentValidationServiceImpl(new QuestionHandlerFactory(List.of(new McqSingleHandler()))), usage, scheduler);
        var worker = new GenerationWorkExecutorImpl(work, ai, mapper, clock, metrics);
        var dispatcher = new GenerationWorkDispatcherImpl(worker, orchestration, config, metrics);
        try {
            dispatcher.dispatch();
            assertThat(firstWave.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(peakJobs).hasValue(Math.min(4, jobCount));
            assertThat(peakCalls).hasValue(2 * Math.min(4, jobCount));
            assertThat(peaksByJob.values()).allSatisfy(peak -> assertThat(peak.get()).isBetween(1, 2));
            assertThat(completed).hasSize(jobCount).allSatisfy(event ->
                    assertThat(event.getChunkQuestions().values().stream().mapToInt(List::size).sum()).isEqualTo(3));
            assertThat(claims).isEmpty();
            verify(work, never()).failed(any());
        } finally {
            release.countDown(); orchestration.shutdown(); providers.shutdown(); metrics.close();
        }
    }
}
