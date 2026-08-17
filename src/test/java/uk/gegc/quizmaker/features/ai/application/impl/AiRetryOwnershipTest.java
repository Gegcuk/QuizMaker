package uk.gegc.quizmaker.features.ai.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.autoconfigure.retry.SpringAiRetryAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason.STOP;
import static org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.Role.ASSISTANT;

@DisplayName("AI retry ownership")
class AiRetryOwnershipTest {

    private static final Path APPLICATION_PROPERTIES = Path.of("src/main/resources/application.properties");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class));

    @Test
    @DisplayName("Propagates a transient provider failure after one low-level dispatch")
    void transientFailureUsesOneLowLevelDispatch() throws IOException {
        runWithPackagedRetryConfiguration(retryTemplate -> {
            OpenAiApi openAiApi = mock(OpenAiApi.class);
            when(openAiApi.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                    .thenThrow(new TransientAiException("provider unavailable"));
            OpenAiChatModel chatModel = chatModel(openAiApi, retryTemplate);

            assertThatThrownBy(() -> chatModel.call(new Prompt("Generate one question")))
                    .isInstanceOf(TransientAiException.class)
                    .hasMessage("provider unavailable");

            verify(openAiApi, times(1))
                    .chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any());
        });
    }

    @Test
    @DisplayName("Returns a successful provider response after one low-level dispatch")
    void successfulResponseUsesOneLowLevelDispatch() throws IOException {
        runWithPackagedRetryConfiguration(retryTemplate -> {
            OpenAiApi openAiApi = mock(OpenAiApi.class);
            when(openAiApi.chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any()))
                    .thenReturn(ResponseEntity.ok(successfulCompletion()));
            OpenAiChatModel chatModel = chatModel(openAiApi, retryTemplate);

            var response = chatModel.call(new Prompt("Generate one question"));

            assertThat(response.getResult().getOutput().getText()).isEqualTo("Generated question");
            verify(openAiApi, times(1))
                    .chatCompletionEntity(any(OpenAiApi.ChatCompletionRequest.class), any());
        });
    }

    private void runWithPackagedRetryConfiguration(Consumer<RetryTemplate> assertion) throws IOException {
        Properties properties = load(APPLICATION_PROPERTIES);
        String maxAttempts = properties.getProperty("spring.ai.retry.max-attempts");

        contextRunner
                .withPropertyValues("spring.ai.retry.max-attempts=" + maxAttempts)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertion.accept(context.getBean(RetryTemplate.class));
                });
    }

    private OpenAiChatModel chatModel(OpenAiApi openAiApi, RetryTemplate retryTemplate) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model("test-model").build())
                .retryTemplate(retryTemplate)
                .build();
    }

    private OpenAiApi.ChatCompletion successfulCompletion() {
        var message = new OpenAiApi.ChatCompletionMessage("Generated question", ASSISTANT);
        var choice = new OpenAiApi.ChatCompletion.Choice(STOP, 0, message, null);
        var usage = new OpenAiApi.Usage(4, 8, 12);
        return new OpenAiApi.ChatCompletion(
                "completion-id",
                List.of(choice),
                1L,
                "test-model",
                null,
                null,
                "chat.completion",
                usage
        );
    }

    private Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }
}
