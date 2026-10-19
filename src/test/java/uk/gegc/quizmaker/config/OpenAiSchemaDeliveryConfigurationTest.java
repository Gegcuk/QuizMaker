package uk.gegc.quizmaker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.application.SchemaDeliveryMode;
import uk.gegc.quizmaker.features.ai.application.StructuredAiClient;
import uk.gegc.quizmaker.features.ai.application.impl.PromptTemplateServiceImpl;
import uk.gegc.quizmaker.features.ai.application.impl.SpringAiStructuredClient;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.config.OpenAiConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiSchemaDeliveryConfigurationTest {

    @Test
    void configuredOpenAiAdapterRetainsStrictSchemaAndTokenLimit() {
        AtomicReference<Prompt> dispatched = new AtomicReference<>();
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            dispatched.set(invocation.getArgument(0));
            return response();
        });

        context(model).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SchemaDeliveryMode.class)).isEqualTo(SchemaDeliveryMode.OPENAI_STRICT);
            assertThat(context.getBean(StructuredAiClient.class).generateQuestions(request()).getQuestions())
                    .hasSize(1);
            OpenAiChatOptions options = (OpenAiChatOptions) dispatched.get().getOptions();
            assertThat(options.getMaxCompletionTokens()).isEqualTo(16000);
            assertThat(options.getResponseFormat().getJsonSchema().getStrict()).isTrue();
            assertThat(systemText(dispatched.get())).doesNotContain("RESPONSE JSON SCHEMA:");
        });
    }

    @Test
    void configuredGenericAdapterUsesCompletePromptSchemaThroughRealChatClient() {
        AtomicReference<Prompt> dispatched = new AtomicReference<>();
        ChatModel model = prompt -> {
            dispatched.set(prompt);
            return response();
        };

        context(model).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SchemaDeliveryMode.class)).isEqualTo(SchemaDeliveryMode.PROMPT_SCHEMA);
            assertThat(context.getBean(StructuredAiClient.class).generateQuestions(request()).getQuestions())
                    .hasSize(1);
            assertThat(dispatched.get().getOptions()).isNotInstanceOf(OpenAiChatOptions.class);
            assertThat(systemText(dispatched.get()))
                    .contains("RESPONSE JSON SCHEMA:", "\"questions\"", "\"confidence\"")
                    .doesNotContain("API enforces this JSON schema");
        });
    }

    private ApplicationContextRunner context(ChatModel model) {
        ObjectMapper mapper = new ObjectMapper();
        AiRateLimitConfig rateLimitConfig = new AiRateLimitConfig();
        rateLimitConfig.setMaxRetries(1);
        return new ApplicationContextRunner()
                .withUserConfiguration(OpenAiConfig.class, SpringAiStructuredClient.class)
                .withBean(ChatModel.class, () -> model)
                .withBean(ObjectMapper.class, () -> mapper)
                .withBean(QuestionSchemaRegistry.class, () -> new QuestionSchemaRegistry(mapper))
                .withBean(PromptTemplateService.class,
                        () -> new PromptTemplateServiceImpl(new DefaultResourceLoader()))
                .withBean(AiRateLimitConfig.class, () -> rateLimitConfig);
    }

    private StructuredQuestionRequest request() {
        return StructuredQuestionRequest.builder().chunkContent("The Earth orbits the Sun.")
                .questionType(QuestionType.TRUE_FALSE).questionCount(1)
                .difficulty(Difficulty.MEDIUM).language("en").build();
    }

    private String systemText(Prompt prompt) {
        return prompt.getInstructions().stream().filter(SystemMessage.class::isInstance)
                .map(message -> ((SystemMessage) message).getText()).findFirst().orElseThrow();
    }

    private ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"questions":[{"questionText":"The Earth revolves around the Sun.","type":"TRUE_FALSE",
                  "difficulty":"MEDIUM","content":{"answer":true},"hint":"Consider the solar system.",
                  "explanation":"Earth orbits the Sun.","confidence":0.98}]}
                """))));
    }
}
