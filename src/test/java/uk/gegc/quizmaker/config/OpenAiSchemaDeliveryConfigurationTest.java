package uk.gegc.quizmaker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiSchemaDeliveryConfigurationTest {

    @ParameterizedTest
    @EnumSource(ProxyKind.class)
    void configuredOpenAiAdapterRetainsStrictSchemaAndTokenLimit(ProxyKind proxyKind) {
        AtomicReference<Prompt> dispatched = new AtomicReference<>();
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            dispatched.set(invocation.getArgument(0));
            return response();
        });

        context(proxied(model, proxyKind)).run(context -> {
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

    private ChatModel proxied(ChatModel model, ProxyKind kind) {
        if (kind == ProxyKind.NONE) {
            return model;
        }
        if (kind == ProxyKind.NESTED) {
            return proxied(proxied(model, ProxyKind.CLASS), ProxyKind.JDK);
        }
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(model);
        factory.setInterfaces(ChatModel.class);
        factory.setProxyTargetClass(kind == ProxyKind.CLASS);
        return (ChatModel) factory.getProxy();
    }

    private enum ProxyKind {
        NONE, JDK, CLASS, NESTED
    }

    @ParameterizedTest
    @EnumSource(ProxyKind.class)
    void configuredGenericAdapterUsesCompletePromptSchemaThroughRealChatClient(ProxyKind proxyKind) {
        RecordingChatModel model = new RecordingChatModel();

        context(proxied(model, proxyKind)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SchemaDeliveryMode.class)).isEqualTo(SchemaDeliveryMode.PROMPT_SCHEMA);
            assertThat(context.getBean(StructuredAiClient.class).generateQuestions(request()).getQuestions())
                    .hasSize(1);
            assertThat(model.dispatched.getOptions()).isNotInstanceOf(OpenAiChatOptions.class);
            assertThat(systemText(model.dispatched))
                    .contains("RESPONSE JSON SCHEMA:", "\"questions\"", "\"confidence\"")
                    .doesNotContain("API enforces this JSON schema");
        });
    }

    @Test
    void strictSchemaSurvivesNativeOpenAiOptionMergingAndHttpSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder restClient = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
        String providerResponse = mapper.writeValueAsString(Map.of(
                "id", "offline-completion", "object", "chat.completion", "created", 1,
                "model", "offline-test-model",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", questionJson()))),
                "usage", Map.of("prompt_tokens", 5, "completion_tokens", 10, "total_tokens", 15)));
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    var body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("model").asText()).isEqualTo("offline-test-model");
                    assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(16000);
                    var format = body.path("response_format");
                    assertThat(format.path("type").asText()).isEqualTo("json_schema");
                    assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
                    assertThat(format.path("json_schema").path("schema")).isEqualTo(
                            new QuestionSchemaRegistry(mapper).getSchemaForQuestionTypeAi(
                                    QuestionType.TRUE_FALSE, Difficulty.MEDIUM));
                    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
                    assertThat(body.path("messages").get(0).path("content").asText())
                            .doesNotContain("RESPONSE JSON SCHEMA:", "The Earth orbits the Sun.");
                    assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
                    assertThat(body.path("messages").get(1).path("content").asText())
                            .contains("QUIZMAKER_UNTRUSTED_SOURCE_", "The Earth orbits the Sun.");
                })
                .andRespond(withSuccess(providerResponse, MediaType.APPLICATION_JSON));
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl("http://localhost").apiKey("offline-test-key")
                        .restClientBuilder(restClient).build())
                .defaultOptions(OpenAiChatOptions.builder().model("offline-test-model")
                        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, (String) null))
                        .build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();

        context(model).run(context -> {
            assertThat(context).hasNotFailed();
            var result = context.getBean(StructuredAiClient.class).generateQuestions(request());
            assertThat(result.getQuestions()).hasSize(1);
            assertThat(result.getTokensUsed()).isEqualTo(15L);
        });
        server.verify();
    }

    static class RecordingChatModel implements ChatModel {
        private Prompt dispatched;

        @Override
        public ChatResponse call(Prompt prompt) {
            dispatched = prompt;
            return response();
        }
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

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(questionJson()))));
    }

    private static String questionJson() {
        return """
                {"questions":[{"questionText":"The Earth revolves around the Sun.","type":"TRUE_FALSE",
                  "difficulty":"MEDIUM","content":{"answer":true},"hint":"Consider the solar system.",
                  "explanation":"Earth orbits the Sun.","confidence":0.98}]}
                """;
    }
}
