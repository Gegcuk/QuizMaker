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
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudget;
import uk.gegc.quizmaker.features.ai.application.ProviderAttemptBudgetExhaustedException;
import uk.gegc.quizmaker.features.ai.application.ProviderUsageObservation;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Spring AI structured client provider attempt budget")
class SpringAiStructuredClientAttemptBudgetTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private AiRateLimitConfig rateLimitConfig;

    private SpringAiStructuredClient client;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        client = new SpringAiStructuredClient(
                chatClient,
                new QuestionSchemaRegistry(objectMapper),
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );

        lenient().when(rateLimitConfig.getMaxRetries()).thenReturn(5);
        lenient().when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("Generate a true/false question");
        lenient().when(promptTemplateService.buildSystemPrompt()).thenReturn("Return JSON only");
    }

    @Test
    @DisplayName("Stops retries after exactly the shared number of provider dispatches")
    void stopsRetriesAtSharedProviderDispatchLimit() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(2);
        stubProviderCall();
        when(callResponseSpec.chatResponse()).thenThrow(new AiServiceException("temporary provider failure"));

        assertThatThrownBy(() -> client.generateQuestions(request(budget, () -> false)))
                .isInstanceOf(ProviderAttemptBudgetExhaustedException.class)
                .hasMessage(ProviderAttemptBudgetExhaustedException.MESSAGE);

        assertThat(budget.consumedAttempts()).isEqualTo(2);
        assertThat(budget.remainingAttempts()).isZero();
        verify(chatClient, times(2)).prompt(any(Prompt.class));
        verify(callResponseSpec, times(2)).chatResponse();
    }

    @Test
    @DisplayName("Consumes one permit for one successful provider dispatch")
    void consumesOnePermitForSuccessfulDispatch() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(3);
        List<ProviderUsageObservation> observations = new ArrayList<>();
        stubProviderCall();
        when(callResponseSpec.chatResponse()).thenReturn(validResponse());

        var response = client.generateQuestions(request(budget, () -> false, observations::add));

        assertThat(response.getQuestions()).singleElement().satisfies(question -> {
            assertThat(question.getType()).isEqualTo(QuestionType.TRUE_FALSE);
            assertThat(question.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
        });
        assertThat(response.getTokensUsed()).isEqualTo(30L);
        assertThat(observations).extracting(ProviderUsageObservation::state).containsExactly(
                ProviderUsageObservation.State.STARTED, ProviderUsageObservation.State.REPORTED);
        assertThat(observations.get(1).providerAttemptId()).isEqualTo(observations.get(0).providerAttemptId());
        assertThat(observations.get(1).providerLlmTokens()).isEqualTo(30L);
        assertThat(budget.consumedAttempts()).isEqualTo(1);
        assertThat(budget.remainingAttempts()).isEqualTo(2);
        verify(chatClient).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Prompt construction failure consumes no provider permit")
    void promptConstructionFailureConsumesNoPermit() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(2);
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenThrow(new IllegalStateException("template unavailable"));

        assertThatThrownBy(() -> client.generateQuestions(request(budget, () -> false)))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("PROMPT_CONSTRUCTION");

        assertThat(budget.consumedAttempts()).isZero();
        assertThat(budget.remainingAttempts()).isEqualTo(2);
        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Request validation failure consumes no provider permit")
    void requestValidationFailureConsumesNoPermit() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(2);
        StructuredQuestionRequest invalidRequest = request(budget, () -> false);
        invalidRequest.setChunkContent(" ");

        assertThatThrownBy(() -> client.generateQuestions(invalidRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chunk content cannot be empty");

        assertThat(budget.consumedAttempts()).isZero();
        assertThat(budget.remainingAttempts()).isEqualTo(2);
        verify(promptTemplateService, never())
                .buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString());
        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("Pre-dispatch cancellation consumes no provider permit")
    void cancellationConsumesNoPermit() {
        ProviderAttemptBudget budget = new ProviderAttemptBudget(2);

        var response = client.generateQuestions(request(budget, () -> true));

        assertThat(response.getQuestions()).isEmpty();
        assertThat(response.getWarnings()).containsExactly("Generation cancelled by user");
        assertThat(budget.consumedAttempts()).isZero();
        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("A legacy request without a shared budget retains the configured retry count")
    void requestWithoutBudgetRetainsConfiguredRetries() {
        stubProviderCall();
        when(callResponseSpec.chatResponse()).thenThrow(new AiServiceException("temporary provider failure"));

        assertThatThrownBy(() -> client.generateQuestions(request(null, () -> false)))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("after 5 attempts");

        verify(chatClient, times(5)).prompt(any(Prompt.class));
        verify(callResponseSpec, times(5)).chatResponse();
    }

    private void stubProviderCall() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    private StructuredQuestionRequest request(
            ProviderAttemptBudget budget,
            java.util.function.Supplier<Boolean> cancellationChecker
    ) {
        return request(budget, cancellationChecker, null);
    }

    private StructuredQuestionRequest request(
            ProviderAttemptBudget budget,
            java.util.function.Supplier<Boolean> cancellationChecker,
            Consumer<ProviderUsageObservation> providerUsageObserver
    ) {
        return StructuredQuestionRequest.builder()
                .chunkContent("Water freezes at zero degrees Celsius under standard pressure.")
                .questionType(QuestionType.TRUE_FALSE)
                .questionCount(1)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .cancellationChecker(cancellationChecker)
                .providerAttemptBudget(budget)
                .providerUsageObserver(providerUsageObserver)
                .build();
    }

    private ChatResponse validResponse() {
        String content = """
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
                """;
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("fake-model")
                .usage(new DefaultUsage(20, 10))
                .build();
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                metadata
        );
    }
}
