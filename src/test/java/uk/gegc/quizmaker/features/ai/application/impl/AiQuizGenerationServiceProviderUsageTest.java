package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.ai.application.ProviderUsageObservation;
import uk.gegc.quizmaker.features.ai.application.ProviderUsagePersistenceException;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.infra.parser.QuestionResponseParser;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.question.application.QuestionContentShuffler;
import uk.gegc.quizmaker.features.question.application.QuestionContentValidationService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.testing.DirectAiProviderTaskScheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI quiz generation provider usage tracking")
class AiQuizGenerationServiceProviderUsageTest {

    @Mock private org.springframework.ai.chat.client.ChatClient chatClient;
    @Mock private DocumentRepository documentRepository;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private QuestionResponseParser questionResponseParser;
    @Mock private QuizGenerationJobRepository jobRepository;
    @Mock private UserRepository userRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AiRateLimitConfig rateLimitConfig;
    @Mock private InternalBillingService internalBillingService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private StructuredAiClient structuredAiClient;
    @Mock private QuestionContentShuffler questionContentShuffler;
    @Mock private QuestionContentValidationService questionContentValidationService;
    @Mock private ProviderUsageService providerUsageService;

    private AiQuizGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
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
                questionContentShuffler,
                questionContentValidationService,
                providerUsageService,
                DirectAiProviderTaskScheduler.INSTANCE
        );
    }

    @Test
    @DisplayName("reported provider usage is delegated with its stable attempt identity")
    void reportedProviderUsageIsDelegatedWithAttemptIdentity() {
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        service.recordProviderUsage(jobId, new ProviderUsageObservation(attemptId, 100L));

        verify(providerUsageService).recordReported(jobId, attemptId, 100L);
    }

    @Test
    @DisplayName("missing provider usage is recorded explicitly instead of estimated")
    void missingProviderUsageIsRecordedExplicitly() {
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        service.recordProviderUsage(jobId, new ProviderUsageObservation(attemptId, null));

        verify(providerUsageService).recordMissing(jobId, attemptId);
    }

    @Test
    @DisplayName("provider usage persistence failure is visible to generation")
    void providerUsagePersistenceFailureIsVisibleToGeneration() {
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        doThrow(new IllegalStateException("storage unavailable"))
                .when(providerUsageService).recordReported(jobId, attemptId, 100L);

        assertThatThrownBy(() -> service.recordProviderUsage(
                jobId, new ProviderUsageObservation(attemptId, 100L)))
                .isInstanceOf(ProviderUsagePersistenceException.class)
                .hasMessageContaining(jobId.toString())
                .hasRootCauseMessage("storage unavailable");
    }

    @Test
    @DisplayName("provider usage persistence failure bypasses every question-generation fallback")
    void providerUsagePersistenceFailureBypassesEveryGenerationFallback() {
        UUID jobId = UUID.randomUUID();
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkIndex(0);
        chunk.setContent("This source is long enough for a deterministic generation attempt without a real provider call.");
        ProviderUsagePersistenceException failure = new ProviderUsagePersistenceException(
                "provider usage unavailable",
                new IllegalStateException("storage unavailable")
        );
        when(structuredAiClient.generateQuestions(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.generateQuestionsFromChunkWithJob(
                        chunk,
                        Map.of(QuestionType.MCQ_SINGLE, 2),
                        Difficulty.MEDIUM,
                        jobId,
                        "en"
                ).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);

        verify(structuredAiClient, times(1)).generateQuestions(any());
    }
}
