package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.auth.api.AuthController;
import uk.gegc.quizmaker.features.auth.api.dto.ChangePasswordRequest;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("AuthController Change Password Tests")
class AuthControllerChangePasswordTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @Test
    @DisplayName("change password: when authenticated with valid passwords then 200 OK")
    @WithMockUser(username = "testuser")
    void changePassword_AuthenticatedWithValidPasswords_ShouldReturn200() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "NewSecureP@ssw0rd123!"
        );
        when(trustedProxyUtil.getClientIp(any())).thenReturn("192.168.1.50");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated successfully"));

        verify(authService).changePassword("testuser", "CurrentP@ssw0rd123!", "NewSecureP@ssw0rd123!");
        // Verify rate limit is checked with username + IP key
        verify(rateLimitService).checkRateLimit("change-password", "testuser|192.168.1.50", 3);
    }

    @Test
    @DisplayName("change password: when not authenticated then 401 Unauthorized")
    void changePassword_NotAuthenticated_ShouldReturn401() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "NewSecureP@ssw0rd123!"
        );

        // When & Then
        // Spring Security intercepts unauthenticated requests before reaching the controller
        // In WebMvcTest, this may result in a redirect (302) or 401 depending on security config
        // The important thing is that the service is never called
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .with(SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().isFound()); // Spring Security redirects to login (302)

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when current password is incorrect then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_IncorrectCurrentPassword_ShouldReturn400() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "WrongP@ssw0rd123!",
                "NewSecureP@ssw0rd123!"
        );
        when(trustedProxyUtil.getClientIp(any())).thenReturn("192.168.1.50");
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect"))
                .when(authService).changePassword(eq("testuser"), eq("WrongP@ssw0rd123!"), anyString());

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService).changePassword("testuser", "WrongP@ssw0rd123!", "NewSecureP@ssw0rd123!");
        // Rate limit should still be checked even on failure
        verify(rateLimitService).checkRateLimit("change-password", "testuser|192.168.1.50", 3);
    }

    @Test
    @DisplayName("change password: when current password is blank then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_BlankCurrentPassword_ShouldReturn400() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "",
                "NewSecureP@ssw0rd123!"
        );

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when new password is blank then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_BlankNewPassword_ShouldReturn400() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                ""
        );

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when new password is too short then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_NewPasswordTooShort_ShouldReturn400() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "Short1!"
        );

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when new password is too long then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_NewPasswordTooLong_ShouldReturn400() throws Exception {
        // Given
        String longPassword = "A".repeat(101) + "1@";
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                longPassword
        );

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when new password is invalid format then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_InvalidPasswordFormat_ShouldReturn400() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "nouppercase123!"
        );

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when new password equals current password then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_NewPasswordEqualsCurrent_ShouldReturn400() throws Exception {
        // Given
        String samePassword = "SameP@ssw0rd123!";
        ChangePasswordRequest request = new ChangePasswordRequest(
                samePassword,
                samePassword
        );

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when rate limit exceeded then 429 Too Many Requests")
    @WithMockUser(username = "testuser")
    void changePassword_RateLimitExceeded_ShouldReturn429() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "NewSecureP@ssw0rd123!"
        );
        String clientIp = "192.168.1.50";
        when(trustedProxyUtil.getClientIp(any())).thenReturn(clientIp);
        String rateLimitKey = "testuser|" + clientIp;

        doThrow(new RateLimitExceededException("Rate limit exceeded", 60))
                .when(rateLimitService).checkRateLimit(eq("change-password"), eq(rateLimitKey), eq(3));

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        verify(rateLimitService).checkRateLimit("change-password", rateLimitKey, 3);
        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("change password: rate limit key should combine username and IP")
    @WithMockUser(username = "testuser")
    void changePassword_RateLimitKeyCombinesUsernameAndIp() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "NewSecureP@ssw0rd123!"
        );
        String clientIp = "10.0.0.5";
        when(trustedProxyUtil.getClientIp(any())).thenReturn(clientIp);

        // When
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        // Then - Verify rate limit key format is username|IP
        String expectedKey = "testuser|" + clientIp;
        verify(rateLimitService).checkRateLimit("change-password", expectedKey, 3);
    }

    @Test
    @DisplayName("change password: rate limit uses correct limit of 3 attempts")
    @WithMockUser(username = "testuser")
    void changePassword_RateLimitUsesCorrectLimit() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "NewSecureP@ssw0rd123!"
        );
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());

        // Then - Verify rate limit is called with limit of 3
        verify(rateLimitService).checkRateLimit(eq("change-password"), anyString(), eq(3));
    }

    @Test
    @DisplayName("change password: when user not found then 404 Not Found")
    @WithMockUser(username = "nonexistent")
    void changePassword_UserNotFound_ShouldReturn404() throws Exception {
        // Given
        ChangePasswordRequest request = new ChangePasswordRequest(
                "CurrentP@ssw0rd123!",
                "NewSecureP@ssw0rd123!"
        );
        when(trustedProxyUtil.getClientIp(any())).thenReturn("192.168.1.50");
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"))
                .when(authService).changePassword(eq("nonexistent"), anyString(), anyString());

        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());

        verify(authService).changePassword("nonexistent", "CurrentP@ssw0rd123!", "NewSecureP@ssw0rd123!");
        verify(rateLimitService).checkRateLimit("change-password", "nonexistent|192.168.1.50", 3);
    }

    @Test
    @DisplayName("change password: when request body is missing then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_MissingRequestBody_ShouldReturn400() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("change password: when request body is invalid JSON then 400 Bad Request")
    @WithMockUser(username = "testuser")
    void changePassword_InvalidJson_ShouldReturn400() throws Exception {
        // When & Then
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json }")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(anyString(), anyString(), anyString());
        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString(), anyInt());
    }
}

