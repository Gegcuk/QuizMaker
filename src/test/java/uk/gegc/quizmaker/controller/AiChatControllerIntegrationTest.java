package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uk.gegc.quizmaker.exception.AiServiceException;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class AiChatControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void testChatEndpointWithValidMessage() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String jsonRequest = """
            {"message": "Hello, how are you?"}
        """;

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.model").exists())
                .andExpect(jsonPath("$.latency").exists())
                .andExpect(jsonPath("$.tokensUsed").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithEmptyMessage() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String jsonRequest = """
            {"message": ""}
        """;

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.details").exists());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithNullMessage() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String jsonRequest = """
            {"message": null}
        """;

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.details").exists());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithTooLongMessage() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String longMessage = "A".repeat(2001); // Exceeds 2000 character limit
        String jsonRequest = """
            {"message": "%s"}
        """.formatted(longMessage);

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.details").exists());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithSpecialCharacters() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String jsonRequest = """
            {"message": "What is 2+2? Can you explain with emojis? 🧮✨"}
        """;

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.model").exists());
    }

    @Test
    void testChatEndpointWithoutAuthentication() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String jsonRequest = """
            {"message": "Hello AI"}
        """;

        // When & Then - Should fail without authentication (Spring Security returns 403 Forbidden)
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithoutCsrf() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String jsonRequest = """
            {"message": "Hello AI"}
        """;

        // When & Then - Should succeed without CSRF token since CSRF is disabled in SecurityConfig
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.model").exists());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithInvalidJson() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String invalidJson = """
            {"message": "Hello AI"
        """; // Missing closing brace

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void testChatEndpointWithMissingMessageField() throws Exception {
        // Given
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        String jsonRequest = """
            {"otherField": "Hello AI"}
        """;

        // When & Then
        mockMvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }
} 