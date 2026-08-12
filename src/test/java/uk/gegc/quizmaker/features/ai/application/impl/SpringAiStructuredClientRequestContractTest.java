package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpringAiStructuredClient - Request Contract")
class SpringAiStructuredClientRequestContractTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private AiRateLimitConfig rateLimitConfig;

    private ObjectMapper objectMapper;
    private SpringAiStructuredClient structuredClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        structuredClient = new SpringAiStructuredClient(
                chatClient,
                new QuestionSchemaRegistry(objectMapper),
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );

        when(rateLimitConfig.getMaxRetries()).thenReturn(1);
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("Generate one question");
        when(promptTemplateService.buildSystemPrompt()).thenReturn("Return JSON only");
    }

    @Test
    @DisplayName("Matching provider output is returned and schema is request-specific")
    void matchingProviderOutputIsReturnedWithRequestSpecificSchema() throws Exception {
        stubProviderResponse("""
                {
                  "questions": [
                    {
                      "questionText": "The Earth revolves around the Sun.",
                      "type": "TRUE_FALSE",
                      "difficulty": "MEDIUM",
                      "content": {"answer": true},
                      "hint": "Consider the solar system.",
                      "explanation": "Earth orbits the Sun.",
                      "confidence": 0.98
                    }
                  ]
                }
                """);

        StructuredQuestionResponse response = structuredClient.generateQuestions(request());

        assertThat(response.getQuestions()).singleElement().satisfies(question -> {
            assertThat(question.getType()).isEqualTo(QuestionType.TRUE_FALSE);
            assertThat(question.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
        });
        assertThat(response.getWarnings()).isEmpty();

        JsonNode schema = capturedRequestSchema();
        JsonNode questionProperties = schema
                .path("properties")
                .path("questions")
                .path("items")
                .path("properties");
        assertThat(questionProperties.path("type").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("TRUE_FALSE");
        assertThat(questionProperties.path("difficulty").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("MEDIUM");
    }

    @Test
    @DisplayName("All wrong-type provider output is rejected")
    void allWrongTypeProviderOutputIsRejected() {
        stubProviderResponse("""
                {
                  "questions": [
                    {
                      "questionText": "Explain how the Earth moves.",
                      "type": "OPEN",
                      "difficulty": "MEDIUM",
                      "content": {"answer": "Earth rotates and orbits the Sun."},
                      "hint": "Consider rotation and revolution.",
                      "explanation": "Both motions describe how Earth moves.",
                      "confidence": 0.91
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> structuredClient.generateQuestions(request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("No valid questions parsed from response");
    }

    @Test
    @DisplayName("All wrong-difficulty provider output is rejected")
    void allWrongDifficultyProviderOutputIsRejected() {
        stubProviderResponse("""
                {
                  "questions": [
                    {
                      "questionText": "The Earth revolves around the Sun.",
                      "type": "TRUE_FALSE",
                      "difficulty": "HARD",
                      "content": {"answer": true},
                      "hint": "Consider the solar system.",
                      "explanation": "Earth orbits the Sun.",
                      "confidence": 0.98
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> structuredClient.generateQuestions(request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("No questions matched requested difficulty MEDIUM");
    }

    @Test
    @DisplayName("Unknown provider question type is rejected")
    void unknownProviderQuestionTypeIsRejected() {
        stubProviderResponse("""
                {
                  "questions": [
                    {
                      "questionText": "The Earth revolves around the Sun.",
                      "type": "UNSUPPORTED_TYPE",
                      "difficulty": "MEDIUM",
                      "content": {"answer": true},
                      "hint": "Consider the solar system.",
                      "explanation": "Earth orbits the Sun.",
                      "confidence": 0.98
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> structuredClient.generateQuestions(request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("No valid questions parsed from response");
    }

    @Test
    @DisplayName("Unknown provider difficulty is rejected")
    void unknownProviderDifficultyIsRejected() {
        stubProviderResponse("""
                {
                  "questions": [
                    {
                      "questionText": "The Earth revolves around the Sun.",
                      "type": "TRUE_FALSE",
                      "difficulty": "IMPOSSIBLE",
                      "content": {"answer": true},
                      "hint": "Consider the solar system.",
                      "explanation": "Earth orbits the Sun.",
                      "confidence": 0.98
                    }
                  ]
                }
                """);

        assertThatThrownBy(() -> structuredClient.generateQuestions(request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("No valid questions parsed from response");
    }

    @Test
    @DisplayName("Mixed provider output retains only exact type and difficulty matches")
    void mixedProviderOutputRetainsOnlyExactMatches() {
        stubProviderResponse("""
                {
                  "questions": [
                    {
                      "questionText": "Explain how the Earth moves.",
                      "type": "OPEN",
                      "difficulty": "MEDIUM",
                      "content": {"answer": "Earth rotates and orbits the Sun."},
                      "hint": "Consider rotation and revolution.",
                      "explanation": "Both motions describe how Earth moves.",
                      "confidence": 0.91
                    },
                    {
                      "questionText": "The Earth revolves around the Sun.",
                      "type": "TRUE_FALSE",
                      "difficulty": "HARD",
                      "content": {"answer": true},
                      "hint": "Consider the solar system.",
                      "explanation": "Earth orbits the Sun.",
                      "confidence": 0.98
                    },
                    {
                      "questionText": "Earth completes one orbit in roughly one year.",
                      "type": "TRUE_FALSE",
                      "difficulty": "MEDIUM",
                      "content": {"answer": true},
                      "hint": "Think about the length of a year.",
                      "explanation": "A year measures one Earth orbit.",
                      "confidence": 0.96
                    }
                  ]
                }
                """);

        StructuredQuestionResponse response = structuredClient.generateQuestions(request());

        assertThat(response.getQuestions()).singleElement().satisfies(question -> {
            assertThat(question.getQuestionText()).contains("one orbit");
            assertThat(question.getType()).isEqualTo(QuestionType.TRUE_FALSE);
            assertThat(question.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
        });
        assertThat(response.getWarnings()).anySatisfy(warning -> assertThat(warning)
                .isEqualTo("Question type mismatch: expected TRUE_FALSE but got OPEN"));
        assertThat(response.getWarnings()).anySatisfy(warning -> assertThat(warning)
                .isEqualTo("Question difficulty mismatch: expected MEDIUM but got HARD"));
    }

    private StructuredQuestionRequest request() {
        return StructuredQuestionRequest.builder()
                .chunkContent("Earth revolves around the Sun and completes one orbit in roughly one year.")
                .questionType(QuestionType.TRUE_FALSE)
                .questionCount(1)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .build();
    }

    private void stubProviderResponse(String content) {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private JsonNode capturedRequestSchema() throws Exception {
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(promptCaptor.capture());

        assertThat(promptCaptor.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        Object schema = options.getResponseFormat().getJsonSchema().getSchema();
        return schema instanceof String schemaJson
                ? objectMapper.readTree(schemaJson)
                : objectMapper.valueToTree(schema);
    }
}
