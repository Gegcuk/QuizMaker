package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import uk.gegc.quizmaker.BaseIntegrationTest;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Simplified integration tests for attempt review endpoints.
 * These tests verify that the endpoints are properly wired and accessible,
 * with correct authorization and error handling.
 * 
 * Detailed data shape testing is handled by controller slice tests (AttemptControllerReviewTest).
 */
@DisplayName("Attempt Review Simple Integration Tests")
public class AttemptReviewSimpleIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: endpoint exists and requires authentication")
    void reviewEndpoint_requiresAuthentication() throws Exception {
        // Given
        UUID randomAttemptId = UUID.randomUUID();

        // When & Then - without auth returns 401 or 403 depending on security config
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", randomAttemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());  // Either 401 or 403 is acceptable
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: returns 404 for non-existent attempt")
    @WithMockUser(username = "testuser")
    void reviewEndpoint_nonExistent_returns404() throws Exception {
        // Given
        UUID randomAttemptId = UUID.randomUUID();

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", randomAttemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/answer-key: endpoint exists and requires authentication")
    void answerKeyEndpoint_requiresAuthentication() throws Exception {
        // Given
        UUID randomAttemptId = UUID.randomUUID();

        // When & Then - without auth returns 401 or 403 depending on security config
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/answer-key", randomAttemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());  // Either 401 or 403 is acceptable
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/answer-key: returns 404 for non-existent attempt")
    @WithMockUser(username = "testuser")
    void answerKeyEndpoint_nonExistent_returns404() throws Exception {
        // Given
        UUID randomAttemptId = UUID.randomUUID();

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/answer-key", randomAttemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: accepts query parameters")
    @WithMockUser(username = "testuser")
    void reviewEndpoint_acceptsQueryParameters() throws Exception {
        // Given
        UUID randomAttemptId = UUID.randomUUID();

        // When & Then - query params are accepted without 400 Bad Request
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", randomAttemptId)
                        .param("includeUserAnswers", "false")
                        .param("includeCorrectAnswers", "false")
                        .param("includeQuestionContext", "false")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                // Expecting 404 (not found) rather than 400 (bad request for invalid params)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Endpoints are properly mapped and return JSON")
    @WithMockUser(username = "testuser")
    void reviewEndpoints_properlyMapped() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        // When & Then - verify endpoints return JSON (not HTML or plain text)
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/attempts/{attemptId}/answer-key", attemptId)
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}

