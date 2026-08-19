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
import org.springframework.web.client.ResourceAccessException;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.ProviderUsageObservation;
import uk.gegc.quizmaker.features.ai.application.ProviderUsagePersistenceException;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.ArrayList;
import java.util.List;

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
@DisplayName("Spring AI structured client provider usage")
class SpringAiStructuredClientProviderUsageTest {

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
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("Generate one fill-gap question");
        when(promptTemplateService.buildSystemPrompt()).thenReturn("Return JSON only");
        lenient().when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    @DisplayName("A valid provider response reports its exact usage once")
    void validResponseReportsExactUsageOnce() {
        when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        List<ProviderUsageObservation> observations = new ArrayList<>();
        when(callResponseSpec.chatResponse()).thenAnswer(invocation -> {
            assertThat(observations).singleElement()
                    .extracting(ProviderUsageObservation::state)
                    .isEqualTo(ProviderUsageObservation.State.STARTED);
            return response(validResponse(), 41, 59);
        });

        var result = client.generateQuestions(request(observations));

        assertThat(result.getQuestions()).hasSize(1);
        assertThat(observations).extracting(ProviderUsageObservation::state)
                .containsExactly(ProviderUsageObservation.State.STARTED, ProviderUsageObservation.State.REPORTED);
        assertThat(observations).extracting(ProviderUsageObservation::providerAttemptId).doesNotContainNull();
        assertThat(observations.get(1).providerAttemptId()).isEqualTo(observations.get(0).providerAttemptId());
        assertThat(observations.get(1).providerLlmTokens()).isEqualTo(100L);
        verify(callResponseSpec).chatResponse();
    }

    @Test
    @DisplayName("Every malformed provider response is accounted for before parsing retry")
    void malformedResponsesAreAccountedForBeforeRetry() {
        when(rateLimitConfig.getMaxRetries()).thenReturn(2);
        when(callResponseSpec.chatResponse()).thenReturn(response("{not-json", 10, 15));
        List<ProviderUsageObservation> observations = new ArrayList<>();

        assertThatThrownBy(() -> client.generateQuestions(request(observations)))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("after 2 attempts");

        assertThat(observations).extracting(ProviderUsageObservation::state)
                .containsExactly(
                        ProviderUsageObservation.State.STARTED,
                        ProviderUsageObservation.State.REPORTED,
                        ProviderUsageObservation.State.STARTED,
                        ProviderUsageObservation.State.REPORTED);
        assertThat(observations.get(0).providerAttemptId()).isEqualTo(observations.get(1).providerAttemptId());
        assertThat(observations.get(2).providerAttemptId()).isEqualTo(observations.get(3).providerAttemptId());
        assertThat(observations.get(0).providerAttemptId()).isNotEqualTo(observations.get(2).providerAttemptId());
        assertThat(observations).extracting(ProviderUsageObservation::providerLlmTokens)
                .containsExactly(null, 25L, null, 25L);
        verify(callResponseSpec, times(2)).chatResponse();
    }

    @Test
    @DisplayName("Missing provider usage metadata is observed without guessing tokens")
    void missingMetadataIsObservedWithoutGuessing() {
        when(rateLimitConfig.getMaxRetries()).thenReturn(1);
        when(callResponseSpec.chatResponse()).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(validResponse())))));
        List<ProviderUsageObservation> observations = new ArrayList<>();

        client.generateQuestions(request(observations));

        assertThat(observations).extracting(ProviderUsageObservation::state)
                .containsExactly(ProviderUsageObservation.State.STARTED, ProviderUsageObservation.State.MISSING);
        assertThat(observations.get(1).providerAttemptId()).isEqualTo(observations.get(0).providerAttemptId());
        assertThat(observations.get(1).providerLlmTokens()).isNull();
    }

    @Test
    @DisplayName("A throwing provider terminalizes the durable attempt before retry handling")
    void providerFailureIsObserved() {
        when(rateLimitConfig.getMaxRetries()).thenReturn(1);
        when(callResponseSpec.chatResponse()).thenThrow(new ResourceAccessException("fake timeout"));
        List<ProviderUsageObservation> observations = new ArrayList<>();

        assertThatThrownBy(() -> client.generateQuestions(request(observations)))
                .isInstanceOf(AiServiceException.class);

        assertThat(observations).extracting(ProviderUsageObservation::state)
                .containsExactly(ProviderUsageObservation.State.STARTED, ProviderUsageObservation.State.FAILED);
        assertThat(observations.get(1).providerAttemptId()).isEqualTo(observations.get(0).providerAttemptId());
    }

    @Test
    @DisplayName("Usage persistence failure stops provider retries after the completed call")
    void persistenceFailureStopsProviderRetries() {
        when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        when(callResponseSpec.chatResponse()).thenReturn(response(validResponse(), 20, 30));
        StructuredQuestionRequest request = baseRequestBuilder()
                .providerUsageObserver(observation -> {
                    if (observation.state() == ProviderUsageObservation.State.REPORTED) {
                        throw new ProviderUsagePersistenceException(
                                "deterministic storage failure", new IllegalStateException("offline"));
                    }
                })
                .build();

        assertThatThrownBy(() -> client.generateQuestions(request))
                .isInstanceOf(ProviderUsagePersistenceException.class)
                .hasMessageContaining("deterministic storage failure");

        verify(callResponseSpec, times(1)).chatResponse();
    }

    @Test
    @DisplayName("Start persistence failure prevents provider dispatch")
    void startPersistenceFailurePreventsProviderDispatch() {
        when(rateLimitConfig.getMaxRetries()).thenReturn(3);
        StructuredQuestionRequest request = baseRequestBuilder()
                .providerUsageObserver(observation -> {
                    throw new ProviderUsagePersistenceException(
                            "deterministic storage failure", new IllegalStateException("offline"));
                })
                .build();

        assertThatThrownBy(() -> client.generateQuestions(request))
                .isInstanceOf(ProviderUsagePersistenceException.class)
                .hasMessageContaining("deterministic storage failure");

        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    private StructuredQuestionRequest request(List<ProviderUsageObservation> observations) {
        return baseRequestBuilder().providerUsageObserver(observations::add).build();
    }

    private StructuredQuestionRequest.StructuredQuestionRequestBuilder baseRequestBuilder() {
        return StructuredQuestionRequest.builder()
                .chunkContent("Cellular respiration produces energy for a living cell.")
                .questionType(QuestionType.FILL_GAP)
                .questionCount(1)
                .difficulty(Difficulty.MEDIUM)
                .language("en");
    }

    private ChatResponse response(String content, int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("fake-model")
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                metadata
        );
    }

    private String validResponse() {
        return """
                {
                  "questions": [
                    {
                      "questionText": "Complete the sentence.",
                      "type": "FILL_GAP",
                      "difficulty": "MEDIUM",
                      "content": {
                        "text": "Respiration produces {1}.",
                        "gaps": [{"id": 1, "answer": "ATP"}],
                        "options": ["ATP", "DNA", "RNA", "water", "carbon", "oxygen", "glucose"]
                      },
                      "hint": "Energy currency",
                      "explanation": "ATP stores usable cellular energy.",
                      "confidence": 0.95
                    }
                  ]
                }
                """;
    }
}
