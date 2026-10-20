package uk.gegc.quizmaker.features.ai.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import uk.gegc.quizmaker.features.ai.api.dto.StructuredQuestionRequest;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.ai.infra.schema.QuestionSchemaRegistry;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpringAiStructuredClientHotspotTest {
    @Mock ChatClient chat;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.CallResponseSpec responseSpec;
    @Mock PromptTemplateService prompts;
    @Mock AiRateLimitConfig limits;
    private final ObjectMapper json = new ObjectMapper();
    private SpringAiStructuredClient client;

    @BeforeEach
    void setUp() {
        client = new SpringAiStructuredClient(chat, new QuestionSchemaRegistry(json), prompts, json, limits);
        when(limits.getMaxRetries()).thenReturn(1);
        when(prompts.buildPromptForChunk(anyString(), any(), anyInt(), any(), anyString())).thenReturn("Describe the supplied diagram.");
        when(prompts.buildSystemPrompt()).thenReturn("Return JSON.");
        when(chat.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
    }

    @Test
    void acceptsCompleteDescriptionsAndStrictSchemaRequiresThem() throws Exception {
        var content = (ObjectNode) QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR);
        respond(content);
        var result = client.generateQuestions(request());
        assertThat(result.getQuestions()).singleElement().satisfies(question -> {
            assertThat(question.getType()).isEqualTo(QuestionType.HOTSPOT);
            assertThat(question.getContent()).contains("imageDescription", "label", "description");
        });
        var schema = new QuestionSchemaRegistry(json).getSchemaForQuestionType(QuestionType.HOTSPOT)
                .at("/properties/questions/items/properties/content");
        assertThat(schema.path("required").toString()).contains("imageDescription");
        assertThat(schema.at("/properties/regions/items/required").toString()).contains("label", "description");
        assertThat(schema.at("/properties/regions/items/properties/label/maxLength").asInt()).isEqualTo(120);
        verify(chat).prompt(any(Prompt.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "region", "duplicate", "blank", "oversized"})
    void rejectsIncompleteOrAmbiguousAiDescriptionsWithoutAcceptingTheCandidate(String scenario) {
        var content = (ObjectNode) QuestionContracts.example(QuestionType.HOTSPOT, QuestionContracts.View.AUTHOR);
        switch (scenario) {
            case "image" -> content.remove("imageDescription");
            case "region" -> ((ObjectNode) content.path("regions").get(0)).remove("description");
            case "duplicate" -> ((ObjectNode) content.path("regions").get(1)).put("label", " CIRCLE ");
            case "blank" -> content.put("imageDescription", "  ");
            case "oversized" -> content.put("imageDescription", "private-canary".repeat(200));
        }
        respond(content);
        assertThatThrownBy(() -> client.generateQuestions(request()))
                .isInstanceOf(AiServiceException.class).hasMessageNotContaining("private-canary");
        verify(chat).prompt(any(Prompt.class));
    }

    private void respond(ObjectNode content) {
        var root = json.createObjectNode();
        var question = root.putArray("questions").addObject();
        question.put("questionText", "Select the striped shape in the diagram.")
                .put("type", "HOTSPOT").put("difficulty", "EASY")
                .put("hint", "Look at the outlines.").put("explanation", "The oval has stripes.").put("confidence", 0.95);
        question.set("content", content);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(root.toString())))));
    }

    private StructuredQuestionRequest request() {
        return StructuredQuestionRequest.builder().chunkContent("The image contains a circle and a striped oval.")
                .questionType(QuestionType.HOTSPOT).questionCount(1).difficulty(Difficulty.EASY).language("en").build();
    }
}
