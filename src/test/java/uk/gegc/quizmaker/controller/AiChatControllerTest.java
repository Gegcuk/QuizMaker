package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.ai.api.dto.ChatResponseDto;
import uk.gegc.quizmaker.features.ai.api.AiChatController;
import uk.gegc.quizmaker.service.AiChatService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiChatController.class)
class AiChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiChatService aiChatService;

    @Test
    @WithMockUser
    void testChatEndpoint() throws Exception {
        // Given
        String jsonRequest = """
                    {"message": "Hello AI"}
                """;
        ChatResponseDto mockResponse = new ChatResponseDto(
                "Hello! How can I help you today?",
                "gpt-4.1-mini",
                1500L,
                25
        );

        when(aiChatService.sendMessage(anyString())).thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello! How can I help you today?"))
                .andExpect(jsonPath("$.model").value("gpt-4.1-mini"))
                .andExpect(jsonPath("$.latency").value(1500))
                .andExpect(jsonPath("$.tokensUsed").value(25))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithSpecialCharacters() throws Exception {
        // Given
        String jsonRequest = """
                    {"message": "What is 2+2?"}
                """;
        ChatResponseDto mockResponse = new ChatResponseDto(
                "2+2 equals 4!",
                "gpt-4.1-mini",
                800L,
                15
        );

        when(aiChatService.sendMessage(anyString())).thenReturn(mockResponse);

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("2+2 equals 4!"))
                .andExpect(jsonPath("$.model").value("gpt-4.1-mini"));
    }
} 