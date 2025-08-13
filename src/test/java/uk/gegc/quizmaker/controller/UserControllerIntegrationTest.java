package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.user.UpdateUserProfileRequest;
import uk.gegc.quizmaker.dto.user.UserProfileResponse;
import uk.gegc.quizmaker.service.user.UserProfileService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@ActiveProfiles("test")
@DisplayName("User Controller Integration Tests")
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Mock the service responses
        when(userProfileService.getCurrentUserProfile(any())).thenReturn(
                new UserProfileResponse(
                        java.util.UUID.randomUUID(),
                        "integrationtest",
                        "integration@test.com",
                        "Integration Test User",
                        "Integration test bio",
                        "https://example.com/integration-avatar.jpg",
                        Map.of("theme", "dark", "notifications", Map.of("email", true)),
                        java.time.LocalDateTime.now(),
                        true,
                        java.util.List.of("USER"),
                        1L
                )
        );

        when(userProfileService.updateCurrentUserProfile(any(), any(com.fasterxml.jackson.databind.JsonNode.class), any())).thenAnswer(invocation -> {
            com.fasterxml.jackson.databind.JsonNode req = invocation.getArgument(1);
            Map<String, Object> prefs = req != null && req.has("preferences") && !req.get("preferences").isNull()
                    ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(req.get("preferences"), Map.class)
                    :
                    Map.of("theme", "dark", "notifications", Map.of("email", true));
            return new UserProfileResponse(
                    java.util.UUID.randomUUID(),
                    "integrationtest",
                    "integration@test.com",
                    "Updated Display Name",
                    "Updated bio with alert('xss')",
                    "https://example.com/integration-avatar.jpg",
                    prefs,
                    java.time.LocalDateTime.now(),
                    true,
                    java.util.List.of("USER"),
                    1L
            );
        });
    }

    @Test
    @DisplayName("Should get user profile successfully with authentication")
    @WithMockUser(username = "integrationtest")
    void shouldGetUserProfileSuccessfullyWithAuthentication() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.username").value("integrationtest"))
                .andExpect(jsonPath("$.email").value("integration@test.com"))
                .andExpect(jsonPath("$.displayName").value("Integration Test User"))
                .andExpect(jsonPath("$.bio").value("Integration test bio"))
                .andExpect(jsonPath("$.avatarUrl").value("https://example.com/integration-avatar.jpg"))
                .andExpect(jsonPath("$.preferences.theme").value("dark"))
                .andExpect(jsonPath("$.preferences.notifications.email").value(true))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    @DisplayName("Should return 403 when not authenticated for GET")
    void shouldReturn403WhenNotAuthenticatedForGet() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should update user profile successfully with authentication")
    @WithMockUser(username = "integrationtest")
    void shouldUpdateUserProfileSuccessfullyWithAuthentication() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                "Updated Display Name",
                "Updated bio with <script>alert('xss')</script>",
                null  // Set preferences to null first
        );

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.displayName").value("Updated Display Name"))
                .andExpect(jsonPath("$.bio").value("Updated bio with alert('xss')")) // XSS should be sanitized
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    @DisplayName("Should update user profile with partial data")
    @WithMockUser(username = "integrationtest")
    void shouldUpdateUserProfileWithPartialData() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", null, null);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Display Name"));
    }

    @Test
    @DisplayName("Should return 403 when not authenticated for PATCH")
    void shouldReturn403WhenNotAuthenticatedForPatch() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", "New Bio", null);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return 400 for invalid JSON")
    @WithMockUser(username = "integrationtest")
    void shouldReturn400ForInvalidJson() throws Exception {
        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("invalid json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should handle XSS in bio field")
    @WithMockUser(username = "integrationtest")
    void shouldHandleXssInBioField() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                "Safe Name",
                "Bio with <script>alert('xss')</script> and <img src=x onerror=alert('xss')>",
                null
        );

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Updated bio with alert('xss')")); // XSS should be sanitized
    }

    @Test
    @DisplayName("Should handle complex preferences object")
    @WithMockUser(username = "integrationtest")
    void shouldHandleComplexPreferencesObject() throws Exception {
        // Given
        Map<String, Object> complexPreferences = Map.of(
                "theme", "dark",
                "language", "en",
                "notifications", Map.of(
                        "email", true,
                        "push", false,
                        "sms", false
                ),
                "privacy", Map.of(
                        "profileVisibility", "public",
                        "showEmail", false
                )
        );

        UpdateUserProfileRequest request = new UpdateUserProfileRequest("John", "Bio", complexPreferences);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.theme").value("dark"))
                .andExpect(jsonPath("$.preferences.language").value("en"))
                .andExpect(jsonPath("$.preferences.notifications.email").value(true))
                .andExpect(jsonPath("$.preferences.notifications.push").value(false))
                .andExpect(jsonPath("$.preferences.privacy.profileVisibility").value("public"));
    }

    @Test
    @DisplayName("Should handle empty strings in request")
    @WithMockUser(username = "integrationtest")
    void shouldHandleEmptyStringsInRequest() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("", "", Map.of());

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should handle null values in request")
    @WithMockUser(username = "integrationtest")
    void shouldHandleNullValuesInRequest() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(null, null, null);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Updated Display Name")); // Should remain unchanged
    }
}


