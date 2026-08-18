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
import org.springframework.ai.retry.NonTransientAiException;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.application.AiProviderHttpException;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudget;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudgetExhaustedException;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
@DisplayName("Spring AI structured client provider retry policy")
class SpringAiStructuredClientProviderRetryTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private AiRateLimitConfig rateLimitConfig;

    private RecordingStructuredClient client;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        client = new RecordingStructuredClient(
                chatClient,
                new QuestionSchemaRegistry(objectMapper),
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );

        lenient().when(rateLimitConfig.getMaxRetries()).thenReturn(5);
        lenient().when(rateLimitConfig.getBaseDelayMs()).thenReturn(1_000L);
        lenient().when(rateLimitConfig.getMaxDelayMs()).thenReturn(60_000L);
        lenient().when(rateLimitConfig.getJitterFactor()).thenReturn(0.25);
        lenient().when(promptTemplateService.buildPromptForChunk(
                anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("Generate one true/false question");
        lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("Return JSON only");
        lenient().when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    @DisplayName("Waits at least the provider Retry-After minimum before retrying once")
    void temporaryRateLimitHonorsProviderMinimum() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(5);
        when(callResponseSpec.chatResponse())
                .thenThrow(providerFailure(
                        429,
                        AiProviderHttpException.FailureKind.RATE_LIMIT,
                        Duration.ofSeconds(3)
                ))
                .thenReturn(validResponse());

        var response = client.generateQuestions(request(budget));

        assertThat(response.getQuestions()).hasSize(1);
        assertThat(client.recordedDelays()).singleElement()
                .satisfies(delay -> assertThat(delay).isBetween(3_000L, 3_250L));
        assertThat(budget.consumedAttempts()).isEqualTo(2);
        verify(chatClient, times(2)).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Stops immediately for quota exhaustion without sleeping or leaking provider text")
    void terminalQuotaFailureIsNotRetried() {
        when(callResponseSpec.chatResponse()).thenThrow(providerFailure(
                429,
                AiProviderHttpException.FailureKind.QUOTA_EXHAUSTED,
                null
        ));

        assertThatThrownBy(() -> client.generateQuestions(request(null)))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("after 1 attempt")
                .hasMessageContaining("PROVIDER_TERMINAL")
                .hasMessageNotContaining("quota response body");

        assertThat(client.recordedDelays()).isEmpty();
        verify(chatClient).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Refuses to retry earlier than a Retry-After value above the configured cap")
    void retryAfterAboveConfiguredCapStopsWithoutSleeping() {
        when(callResponseSpec.chatResponse()).thenThrow(providerFailure(
                429,
                AiProviderHttpException.FailureKind.RATE_LIMIT,
                Duration.ofSeconds(61)
        ));

        assertThatThrownBy(() -> client.generateQuestions(request(null)))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("RETRY_DELAY_EXCEEDED");

        assertThat(client.recordedDelays()).isEmpty();
        verify(chatClient).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Uses bounded exponential backoff for a retryable server error without Retry-After")
    void serverErrorWithoutRetryAfterUsesBoundedBackoff() {
        when(callResponseSpec.chatResponse())
                .thenThrow(providerFailure(
                        503,
                        AiProviderHttpException.FailureKind.SERVER_ERROR,
                        null
                ))
                .thenReturn(validResponse());

        var response = client.generateQuestions(request(null));

        assertThat(response.getQuestions()).hasSize(1);
        assertThat(client.recordedDelays()).singleElement()
                .satisfies(delay -> assertThat(delay).isBetween(750L, 1_250L));
        verify(chatClient, times(2)).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Stops immediately for Spring AI non-transient failures")
    void nonTransientSpringAiFailureIsNotRetried() {
        when(callResponseSpec.chatResponse())
                .thenThrow(new NonTransientAiException("private provider response"));

        assertThatThrownBy(() -> client.generateQuestions(request(null)))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("PROVIDER_TERMINAL")
                .hasMessageNotContaining("private provider response");

        assertThat(client.recordedDelays()).isEmpty();
        verify(chatClient).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Keeps the legacy immediate retry for an invalid generated response")
    void invalidGeneratedResponseRetainsLegacyRetryBehavior() {
        when(callResponseSpec.chatResponse())
                .thenReturn(responseWithContent("not-json"))
                .thenReturn(validResponse());

        var response = client.generateQuestions(request(null));

        assertThat(response.getQuestions()).hasSize(1);
        assertThat(client.recordedDelays()).isEmpty();
        verify(chatClient, times(2)).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Keeps bounded backoff for legacy fake exceptions that identify a rate limit")
    void legacyRateLimitMessageRemainsRetryable() {
        when(callResponseSpec.chatResponse())
                .thenThrow(new RuntimeException("429 Too Many Requests: private provider response"))
                .thenReturn(validResponse());

        var response = client.generateQuestions(request(null));

        assertThat(response.getQuestions()).hasSize(1);
        assertThat(client.recordedDelays()).singleElement()
                .satisfies(delay -> assertThat(delay).isBetween(750L, 1_250L));
        verify(chatClient, times(2)).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Does not sleep when the shared provider-attempt budget is already exhausted")
    void exhaustedSharedBudgetStopsBeforeRetryDelay() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(1);
        when(callResponseSpec.chatResponse()).thenThrow(providerFailure(
                503,
                AiProviderHttpException.FailureKind.SERVER_ERROR,
                Duration.ofSeconds(3)
        ));

        assertThatThrownBy(() -> client.generateQuestions(request(budget)))
                .isInstanceOf(ProviderAttemptBudgetExhaustedException.class);

        assertThat(client.recordedDelays()).isEmpty();
        assertThat(budget.consumedAttempts()).isEqualTo(1);
        verify(chatClient).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Restores the interrupt flag when a retry wait is interrupted")
    void interruptedRetryWaitRestoresInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.invokeRealSleep(1))
                    .isInstanceOf(AiServiceException.class)
                    .hasMessageContaining("Interrupted while waiting");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private StructuredQuestionRequest request(ProviderAttemptBudget budget) {
        return StructuredQuestionRequest.builder()
                .chunkContent("Water freezes at zero degrees Celsius under standard pressure.")
                .questionType(QuestionType.TRUE_FALSE)
                .questionCount(1)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .providerAttemptBudget(budget)
                .build();
    }

    private AiProviderHttpException providerFailure(
            int statusCode,
            AiProviderHttpException.FailureKind failureKind,
            Duration retryAfter
    ) {
        return new AiProviderHttpException(statusCode, failureKind, retryAfter);
    }

    private ChatResponse validResponse() {
        return responseWithContent("""
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
                """);
    }

    private ChatResponse responseWithContent(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class RecordingStructuredClient extends SpringAiStructuredClient {

        private final List<Long> recordedDelays = new ArrayList<>();

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
        }

        private List<Long> recordedDelays() {
            return List.copyOf(recordedDelays);
        }

        private void invokeRealSleep(long delayMs) {
            super.sleepForRateLimit(delayMs);
        }
    }
}
