package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests focused on the structured-output wiring in {@link SpringAiStructuredClient}.
 */
class SpringAiStructuredClientStructuredOutputTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private AiRateLimitConfig rateLimitConfig;

    private ObjectMapper objectMapper;
    private QuestionSchemaRegistry schemaRegistry;
    private SpringAiStructuredClient client;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        client = new SpringAiStructuredClient(chatClient, schemaRegistry, promptTemplateService, objectMapper, rateLimitConfig);
    }

    @Test
    @DisplayName("buildChatOptions should configure JSON_SCHEMA response format")
    void buildChatOptionsShouldConfigureJsonSchema() throws Exception {
        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.MCQ_SINGLE);
        Method method = SpringAiStructuredClient.class.getDeclaredMethod("buildChatOptions", QuestionType.class, JsonNode.class);
        method.setAccessible(true);

        OpenAiChatOptions options = (OpenAiChatOptions) method.invoke(client, QuestionType.MCQ_SINGLE, schema);

        assertThat(options).isNotNull();
        ResponseFormat responseFormat = options.getResponseFormat();
        assertThat(responseFormat).isNotNull();
        assertThat(responseFormat.getType()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
        assertThat(responseFormat.getJsonSchema()).isNotNull();
        assertThat(responseFormat.getJsonSchema().getName()).contains("mcq_single");
        assertThat(responseFormat.getJsonSchema().getStrict()).isTrue();
        assertThat(responseFormat.getJsonSchema().getSchema()).isNotEmpty();
    }

    @Test
    @DisplayName("buildChatOptions should return null when schema serialization fails")
    void buildChatOptionsShouldReturnNullOnSerializationFailure() throws Exception {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("boom") { };
            }
        };

        SpringAiStructuredClient failingClient = new SpringAiStructuredClient(
                chatClient,
                schemaRegistry,
                promptTemplateService,
                failingMapper,
                rateLimitConfig
        );

        JsonNode schema = schemaRegistry.getSchemaForQuestionType(QuestionType.TRUE_FALSE);
        Method method = SpringAiStructuredClient.class.getDeclaredMethod("buildChatOptions", QuestionType.class, JsonNode.class);
        method.setAccessible(true);

        OpenAiChatOptions options = (OpenAiChatOptions) method.invoke(failingClient, QuestionType.TRUE_FALSE, schema);

        assertThat(options).isNull();
    }

    @Test
    @DisplayName("System prompt should not embed raw schema text")
    void systemPromptShouldExcludeSchemaBody() throws Exception {
        Method method = SpringAiStructuredClient.class.getDeclaredMethod("buildSystemPrompt");
        method.setAccessible(true);

        String prompt = (String) method.invoke(client);

        assertThat(prompt).isNotBlank();
        assertThat(prompt).doesNotContain("$schema");
        assertThat(prompt).contains("Structured outputs");
    }
}
