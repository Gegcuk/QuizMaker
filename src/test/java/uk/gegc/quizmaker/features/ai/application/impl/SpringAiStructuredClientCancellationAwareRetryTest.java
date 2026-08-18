package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.application.AiProviderHttpException;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudget;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Spring AI structured client cancellation-aware retry waits")
class SpringAiStructuredClientCancellationAwareRetryTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private AiRateLimitConfig rateLimitConfig;

    private ObjectMapper objectMapper;
    private RecordingStructuredClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = recordingClient();

        lenient().when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        lenient().when(rateLimitConfig.getBaseDelayMs()).thenReturn(1_000L);
        lenient().when(rateLimitConfig.getMaxDelayMs()).thenReturn(60_000L);
        lenient().when(rateLimitConfig.getJitterFactor()).thenReturn(0.0);
        lenient().when(promptTemplateService.buildPromptForChunk(
                        anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("Generate one true/false question");
        lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("Return JSON only");
        lenient().when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    @DisplayName("Cancellation during provider backoff stops within one wait slice and suppresses the retry")
    void cancellationDuringProviderBackoffStopsBeforeNextDispatch() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ProviderAttemptBudget budget = new ProviderAttemptBudget(3);
        client.afterNextSleep(() -> cancelled.set(true));
        when(callResponseSpec.chatResponse()).thenThrow(providerFailure(Duration.ofSeconds(5)));

        var response = client.generateQuestions(request(budget, cancelled::get));

        assertThat(response.getQuestions()).isEmpty();
        assertThat(response.getWarnings()).containsExactly("Generation cancelled by user");
        assertThat(response.getTokensUsed()).isZero();
        assertThat(client.recordedDelays()).containsExactly(1_000L);
        assertThat(budget.consumedAttempts()).isEqualTo(1);
        verify(chatClient).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Active generation completes every bounded wait slice before retrying successfully")
    void activeGenerationCompletesFullBackoffBeforeRetry() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(3);
        when(callResponseSpec.chatResponse())
                .thenThrow(providerFailure(Duration.ofMillis(2_500)))
                .thenReturn(validResponse());

        var response = client.generateQuestions(request(budget, () -> false));

        assertThat(response.getQuestions()).hasSize(1);
        assertThat(client.recordedDelays()).containsExactly(1_000L, 1_000L, 500L);
        assertThat(client.recordedDelays()).allMatch(delay -> delay <= 1_000L);
        assertThat(client.recordedDelays().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(2_500L);
        assertThat(budget.consumedAttempts()).isEqualTo(2);
        verify(chatClient, times(2)).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Legacy request without cancellation checker retains one uninterrupted provider wait")
    void requestWithoutCancellationCheckerRetainsLegacyWait() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(3);
        when(callResponseSpec.chatResponse())
                .thenThrow(providerFailure(Duration.ofMillis(2_500)))
                .thenReturn(validResponse());

        var response = client.generateQuestions(request(budget, null));

        assertThat(response.getQuestions()).hasSize(1);
        assertThat(client.recordedDelays()).containsExactly(2_500L);
        assertThat(budget.consumedAttempts()).isEqualTo(2);
        verify(chatClient, times(2)).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Interrupted cancellation-aware wait restores the flag and suppresses the retry")
    void interruptedCancellationAwareWaitStopsBeforeNextDispatch() {
        SpringAiStructuredClient interruptibleClient = new SpringAiStructuredClient(
                chatClient,
                new QuestionSchemaRegistry(objectMapper),
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );
        ProviderAttemptBudget budget = new ProviderAttemptBudget(3);
        when(callResponseSpec.chatResponse()).thenThrow(providerFailure(Duration.ofSeconds(3)));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> interruptibleClient.generateQuestions(request(budget, () -> false)))
                    .isInstanceOf(AiServiceException.class)
                    .hasMessageContaining("Interrupted while waiting");

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(budget.consumedAttempts()).isEqualTo(1);
            verify(chatClient).prompt(any(Prompt.class));
        } finally {
            Thread.interrupted();
        }
    }

    private RecordingStructuredClient recordingClient() {
        return new RecordingStructuredClient(
                chatClient,
                new QuestionSchemaRegistry(objectMapper),
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );
    }

    private StructuredQuestionRequest request(
            ProviderAttemptBudget budget,
            Supplier<Boolean> cancellationChecker
    ) {
        return StructuredQuestionRequest.builder()
                .chunkContent("Water freezes at zero degrees Celsius under standard pressure.")
                .questionType(QuestionType.TRUE_FALSE)
                .questionCount(1)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .providerAttemptBudget(budget)
                .cancellationChecker(cancellationChecker)
                .build();
    }

    private AiProviderHttpException providerFailure(Duration retryAfter) {
        return new AiProviderHttpException(
                503,
                AiProviderHttpException.FailureKind.SERVER_ERROR,
                retryAfter
        );
    }

    private ChatResponse validResponse() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {
                  "questions": [
                    {
                      "questionText": "Water freezes at zero degrees Celsius under standard pressure.",
                      "type": "TRUE_FALSE",
                      "difficulty": "MEDIUM",
                      "content": {"answer": true},
                      "hint": "Think about the freezing point of water.",
                      "explanation": "Zero degrees Celsius is the standard freezing point.",
                      "confidence": 0.98
                    }
                  ]
                }
                """))));
    }

    private static final class RecordingStructuredClient extends SpringAiStructuredClient {

        private final List<Long> recordedDelays = new ArrayList<>();
        private Runnable afterNextSleep = () -> { };

        private RecordingStructuredClient(
                ChatClient chatClient,
                QuestionSchemaRegistry schemaRegistry,
                PromptTemplateService promptTemplateService,
                ObjectMapper objectMapper,
                AiRateLimitConfig rateLimitConfig
        ) {
            super(chatClient, schemaRegistry, promptTemplateService, objectMapper, rateLimitConfig);
        }

        @Override
        void sleepForRateLimit(long delayMs) {
            recordedDelays.add(delayMs);
            Runnable callback = afterNextSleep;
            afterNextSleep = () -> { };
            callback.run();
        }

        private void afterNextSleep(Runnable callback) {
            afterNextSleep = callback;
        }

        private List<Long> recordedDelays() {
            return List.copyOf(recordedDelays);
        }
    }
}
