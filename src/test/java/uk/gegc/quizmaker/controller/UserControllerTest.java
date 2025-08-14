package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.user.api.dto.UpdateUserProfileRequest;
import uk.gegc.quizmaker.features.user.api.dto.UserProfileResponse;
import uk.gegc.quizmaker.features.user.api.UserController;
import uk.gegc.quizmaker.features.user.application.AvatarService;
import uk.gegc.quizmaker.features.user.application.UserProfileService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@DisplayName("User Controller Tests")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @MockitoBean
    private AvatarService avatarService;

    private ObjectMapper objectMapper;
    private UserProfileResponse testUserProfileResponse;
    private UUID userId;
    private LocalDateTime createdAt;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        userId = UUID.randomUUID();
        createdAt = LocalDateTime.now();

        testUserProfileResponse = new UserProfileResponse(
                userId,
                "testuser",
                "test@example.com",
                "Test User",
                "Test bio",
                "https://example.com/avatar.jpg",
                Map.of("theme", "dark"),
                createdAt,
                true,
                List.of("USER"),
                1L
        );
    }

    @Test
    @WithMockUser(username = "testuser")
    @DisplayName("Should get user profile successfully")
    void shouldGetUserProfileSuccessfully() throws Exception {
        // Given
        lenient().when(userProfileService.getCurrentUserProfile(any())).thenReturn(testUserProfileResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.bio").value("Test bio"))
                .andExpect(jsonPath("$.avatarUrl").value("https://example.com/avatar.jpg"))
                .andExpect(jsonPath("$.preferences.theme").value("dark"))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    @WithMockUser(username = "testuser")
    @DisplayName("Should update user profile successfully")
    void shouldUpdateUserProfileSuccessfully() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest(
                "New Display Name",
                "New bio",
                Map.of("theme", "light", "notifications", Map.of("email", false))
        );

        UserProfileResponse updatedResponse = new UserProfileResponse(
                userId,
                "testuser",
                "test@example.com",
                "New Display Name",
                "New bio",
                "https://example.com/avatar.jpg",
                Map.of("theme", "light", "notifications", Map.of("email", false)),
                createdAt,
                true,
                List.of("USER"),
                1L
        );

        lenient().when(userProfileService.updateCurrentUserProfile(any(), any(com.fasterxml.jackson.databind.JsonNode.class), any()))
                .thenReturn(updatedResponse);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.displayName").value("New Display Name"))
                .andExpect(jsonPath("$.bio").value("New bio"))
                .andExpect(jsonPath("$.preferences.theme").value("light"))
                .andExpect(jsonPath("$.preferences.notifications.email").value(false))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    @WithMockUser(username = "testuser")
    @DisplayName("Should update user profile with partial data")
    void shouldUpdateUserProfileWithPartialData() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", null, null);

        UserProfileResponse updatedResponse = new UserProfileResponse(
                userId,
                "testuser",
                "test@example.com",
                "New Name",
                "Test bio",
                "https://example.com/avatar.jpg",
                Map.of("theme", "dark"),
                createdAt,
                true,
                List.of("USER"),
                1L
        );

        lenient().when(userProfileService.updateCurrentUserProfile(any(), any(com.fasterxml.jackson.databind.JsonNode.class), any()))
                .thenReturn(updatedResponse);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("New Name"))
                .andExpect(jsonPath("$.bio").value("Test bio")); // Should remain unchanged
    }

    @Test
    @WithMockUser(username = "testuser")
    @DisplayName("Should update user profile with empty strings")
    void shouldUpdateUserProfileWithEmptyStrings() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("", "", Map.of());

        lenient().when(userProfileService.updateCurrentUserProfile(any(), any(com.fasterxml.jackson.databind.JsonNode.class), any()))
                .thenReturn(testUserProfileResponse);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "testuser")
    @DisplayName("Should update user profile with complex preferences")
    void shouldUpdateUserProfileWithComplexPreferences() throws Exception {
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

        UserProfileResponse updatedResponse = new UserProfileResponse(
                userId,
                "testuser",
                "test@example.com",
                "John",
                "Bio",
                "https://example.com/avatar.jpg",
                complexPreferences,
                createdAt,
                true,
                List.of("USER"),
                1L
        );

        lenient().when(userProfileService.updateCurrentUserProfile(any(), any(com.fasterxml.jackson.databind.JsonNode.class), any()))
                .thenReturn(updatedResponse);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
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
    @WithMockUser(username = "testuser")
    @DisplayName("Should return 400 for invalid JSON in PATCH request")
    void shouldReturn400ForInvalidJsonInPatchRequest() throws Exception {
        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("invalid json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "testuser")
    @DisplayName("Should return 400 for missing content type in PATCH request")
    void shouldReturn400ForMissingContentTypeInPatchRequest() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", "New Bio", null);

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @WithMockUser(username = "testuser")
    @DisplayName("Should handle service exceptions properly")
    void shouldHandleServiceExceptionsProperly() throws Exception {
        // Given
        UpdateUserProfileRequest request = new UpdateUserProfileRequest("New Name", "New Bio", null);

        lenient().when(userProfileService.updateCurrentUserProfile(any(), any(com.fasterxml.jackson.databind.JsonNode.class), any()))
                .thenThrow(new RuntimeException("Service error"));

        // When & Then
        mockMvc.perform(patch("/api/v1/users/me")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }
}


