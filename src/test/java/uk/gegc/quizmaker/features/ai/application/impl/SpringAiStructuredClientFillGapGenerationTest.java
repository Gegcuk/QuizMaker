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
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestion;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionResponse;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpringAiStructuredClient - FILL_GAP Generation")
class SpringAiStructuredClientFillGapGenerationTest {

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
    private QuestionSchemaRegistry schemaRegistry;
    private SpringAiStructuredClient structuredClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        schemaRegistry = new QuestionSchemaRegistry(objectMapper);
        structuredClient = new SpringAiStructuredClient(
                chatClient,
                schemaRegistry,
                promptTemplateService,
                objectMapper,
                rateLimitConfig
        );

        when(rateLimitConfig.getMaxRetries()).thenReturn(1);
        when(promptTemplateService.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString()))
                .thenReturn("Generate fill-gap questions");
        when(promptTemplateService.buildSystemPrompt()).thenReturn("Return JSON only");
    }

    @Test
    @DisplayName("generateQuestions: valid FILL_GAP response with options returns structured question")
    void generateQuestions_fillGapWithOptions_returnsStructuredQuestion() throws Exception {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("""
                {
                  "questions": [
                    {
                      "questionText": "Complete the cellular respiration sentence.",
                      "type": "FILL_GAP",
                      "difficulty": "MEDIUM",
                      "content": {
                        "text": "Cellular respiration occurs in the {1} and produces {2}.",
                        "gaps": [
                          {"id": 1, "answer": "mitochondria"},
                          {"id": 2, "answer": "ATP"}
                        ],
                        "options": ["mitochondria", "ATP", "chloroplast", "ribosome", "nucleus", "glucose", "NADH", "oxygen"]
                      },
                      "hint": "Think about the powerhouse of the cell.",
                      "explanation": "Cellular respiration happens in mitochondria and produces ATP.",
                      "confidence": 0.94
                    }
                  ]
                }
                """));

        StructuredQuestionResponse response = structuredClient.generateQuestions(fillGapRequest());

        assertThat(response.getQuestions()).hasSize(1);
        StructuredQuestion question = response.getQuestions().get(0);
        assertThat(question.getType()).isEqualTo(QuestionType.FILL_GAP);

        JsonNode content = objectMapper.readTree(question.getContent());
        assertThat(content.get("options")).hasSize(8);
        List<String> options = StreamSupport.stream(content.get("options").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
        assertThat(options).contains("mitochondria", "ATP");
        assertThat(content.get("gaps").get(0).get("answer").asText()).isEqualTo("mitochondria");
        assertPromptSchemaRequiresFillGapOptions();
    }

    @Test
    @DisplayName("generateQuestions: missing FILL_GAP options is rejected")
    void generateQuestions_fillGapMissingOptions_rejectsResponse() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("""
                {
                  "questions": [
                    {
                      "questionText": "Complete the sentence.",
                      "type": "FILL_GAP",
                      "difficulty": "MEDIUM",
                      "content": {
                        "text": "Java is a {1} language.",
                        "gaps": [
                          {"id": 1, "answer": "programming"}
                        ]
                      },
                      "hint": "Think about Java.",
                      "explanation": "Java is a programming language.",
                      "confidence": 0.9
                    }
                  ]
                }
                """));

        assertThatThrownBy(() -> structuredClient.generateQuestions(fillGapRequest()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("No valid questions parsed from response");
    }

    @Test
    @DisplayName("generateQuestions: invalid FILL_GAP options is rejected")
    void generateQuestions_fillGapInvalidOptions_rejectsResponse() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("""
                {
                  "questions": [
                    {
                      "questionText": "Complete the sentence.",
                      "type": "FILL_GAP",
                      "difficulty": "MEDIUM",
                      "content": {
                        "text": "Java is a {1} language.",
                        "gaps": [
                          {"id": 1, "answer": "programming"}
                        ],
                        "options": ["markup", "query", "scripting", "compiled", "interpreted", "dynamic", "static"]
                      },
                      "hint": "Think about Java.",
                      "explanation": "Java is a programming language.",
                      "confidence": 0.9
                    }
                  ]
                }
                """));

        assertThatThrownBy(() -> structuredClient.generateQuestions(fillGapRequest()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("No valid questions parsed from response");
    }

    private StructuredQuestionRequest fillGapRequest() {
        return StructuredQuestionRequest.builder()
                .chunkContent("Cellular respiration occurs in mitochondria and produces ATP.")
                .questionType(QuestionType.FILL_GAP)
                .questionCount(1)
                .difficulty(Difficulty.MEDIUM)
                .language("en")
                .build();
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private void assertPromptSchemaRequiresFillGapOptions() {
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatClient).prompt(promptCaptor.capture());

        assertThat(promptCaptor.getValue().getOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        JsonNode schema = objectMapper.valueToTree(options.getResponseFormat().getJsonSchema().getSchema());
        JsonNode required = schema
                .path("properties")
                .path("questions")
                .path("items")
                .path("properties")
                .path("content")
                .path("required");

        assertThat(required.toString()).contains("text", "gaps", "options");
        assertThat(schemaRegistry.getSchemaForQuestionTypeAi(QuestionType.FILL_GAP)
                .path("properties")
                .path("questions")
                .path("items")
                .path("properties")
                .path("content")
                .path("required")
                .toString()).contains("options");
    }
}
