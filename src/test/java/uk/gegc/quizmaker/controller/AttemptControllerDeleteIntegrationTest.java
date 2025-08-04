package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.service.attempt.AttemptService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class AttemptControllerDeleteIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private AttemptService attemptService;

    private MockMvc mockMvc;
    private UUID attemptId;
    private String username;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        attemptId = UUID.randomUUID();
        username = "testuser";
    }

    @Test
    @DisplayName("DELETE /api/v1/attempts/{attemptId}: successfully deletes attempt")
    @WithMockUser(username = "testuser")
    void deleteAttempt_success() throws Exception {
        // Arrange
        doNothing().when(attemptService).deleteAttempt(eq(username), eq(attemptId));

        // Act & Assert
        mockMvc.perform(delete("/api/v1/attempts/{attemptId}", attemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(attemptService).deleteAttempt(username, attemptId);
    }

    @Test
    @DisplayName("DELETE /api/v1/attempts/{attemptId}: returns 404 when attempt not found")
    @WithMockUser(username = "testuser")
    void deleteAttempt_notFound_returns404() throws Exception {
        // Arrange
        doThrow(new ResourceNotFoundException("Attempt " + attemptId + " not found"))
                .when(attemptService).deleteAttempt(eq(username), eq(attemptId));

        // Act & Assert
        mockMvc.perform(delete("/api/v1/attempts/{attemptId}", attemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        verify(attemptService).deleteAttempt(username, attemptId);
    }

    @Test
    @DisplayName("DELETE /api/v1/attempts/{attemptId}: returns 403 when user doesn't own the attempt")
    @WithMockUser(username = "testuser")
    void deleteAttempt_wrongUser_returns403() throws Exception {
        // Arrange
        doThrow(new org.springframework.security.access.AccessDeniedException("You do not have access to attempt " + attemptId))
                .when(attemptService).deleteAttempt(eq(username), eq(attemptId));

        // Act & Assert
        mockMvc.perform(delete("/api/v1/attempts/{attemptId}", attemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verify(attemptService).deleteAttempt(username, attemptId);
    }

    @Test
    @DisplayName("DELETE /api/v1/attempts/{attemptId}: returns 403 when not authenticated")
    void deleteAttempt_unauthenticated_returns403() throws Exception {
        // Act & Assert
        mockMvc.perform(delete("/api/v1/attempts/{attemptId}", attemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        verify(attemptService, never()).deleteAttempt(any(), any());
    }

    @Test
    @DisplayName("DELETE /api/v1/attempts/{attemptId}: returns 400 for invalid UUID format")
    @WithMockUser(username = "testuser")
    void deleteAttempt_invalidUuid_returns400() throws Exception {
        // Act & Assert
        mockMvc.perform(delete("/api/v1/attempts/{attemptId}", "invalid-uuid")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(attemptService, never()).deleteAttempt(any(), any());
    }
}