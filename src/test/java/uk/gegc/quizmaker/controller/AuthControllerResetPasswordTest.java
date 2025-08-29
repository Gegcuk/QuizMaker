package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.auth.api.AuthController;
import uk.gegc.quizmaker.features.auth.api.dto.ResetPasswordRequest;
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
@DisplayName("AuthController Reset Password Tests")
class AuthControllerResetPasswordTest {

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
    @DisplayName("reset password with valid token and password should succeed")
    @WithMockUser
    void resetPassword_ValidTokenAndPassword_ShouldSucceed() throws Exception {
        // Given
        String token = "valid-reset-token";
        ResetPasswordRequest request = new ResetPasswordRequest("NewSecureP@ssw0rd123!");
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated successfully"));

        verify(authService).resetPassword(token, "NewSecureP@ssw0rd123!");
    }

    @Test
    @DisplayName("reset password with missing token should return 400")
    @WithMockUser
    void resetPassword_MissingToken_ShouldReturn400() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("NewSecureP@ssw0rd123!");
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reset password with invalid password should return 400")
    @WithMockUser
    void resetPassword_InvalidPassword_ShouldReturn400() throws Exception {
        // Given
        String token = "valid-reset-token";
        ResetPasswordRequest request = new ResetPasswordRequest("weak");
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reset password with blank password should return 400")
    @WithMockUser
    void resetPassword_BlankPassword_ShouldReturn400() throws Exception {
        // Given
        String token = "valid-reset-token";
        ResetPasswordRequest request = new ResetPasswordRequest("");
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reset password with rate limit exceeded should return 429")
    @WithMockUser
    void resetPassword_RateLimitExceeded_ShouldReturn429() throws Exception {
        // Given
        String token = "valid-reset-token";
        ResetPasswordRequest request = new ResetPasswordRequest("NewSecureP@ssw0rd123!");
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        doThrow(new RateLimitExceededException("Rate limit exceeded", 60))
                .when(rateLimitService).checkRateLimit(eq("reset-password"), any());

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }
}
