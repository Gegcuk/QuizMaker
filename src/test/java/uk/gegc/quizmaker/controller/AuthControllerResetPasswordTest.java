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
import uk.gegc.quizmaker.dto.auth.ResetPasswordRequest;
import uk.gegc.quizmaker.exception.RateLimitExceededException;
import uk.gegc.quizmaker.service.RateLimitService;
import uk.gegc.quizmaker.service.auth.AuthService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@TestPropertySource(properties = {
        "app.auth.reset-token-pepper=test_pepper",
        "spring.mail.username=test@example.com"
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

    @Test
    @DisplayName("resetPassword: valid request should return 200 OK")
    @WithMockUser
    void resetPassword_ValidRequest_ReturnsOk() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "NewP@ssw0rd123!");

        doNothing().when(rateLimitService).checkRateLimit(eq("reset-password"), eq("127.0.0.1|valid-token"));
        doNothing().when(authService).resetPassword("valid-token", "NewP@ssw0rd123!");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Password updated successfully"));
    }

    @Test
    @DisplayName("resetPassword: rate limit exceeded should return 429 with Retry-After header")
    @WithMockUser
    void resetPassword_RateLimitExceeded_ReturnsTooManyRequestsWithRetryAfter() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "NewP@ssw0rd123!");

        doThrow(new RateLimitExceededException("Too many requests for reset-password", 45))
                .when(rateLimitService).checkRateLimit(eq("reset-password"), eq("127.0.0.1|valid-token"));

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(header().string("Retry-After", "45"));
    }

    @Test
    @DisplayName("resetPassword: invalid token should return 409 Conflict")
    @WithMockUser
    void resetPassword_InvalidToken_ReturnsConflict() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("invalid-token", "NewP@ssw0rd123!");

        doNothing().when(rateLimitService).checkRateLimit(eq("reset-password"), eq("127.0.0.1|invalid-token"));
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid or expired reset token"))
                .when(authService).resetPassword("invalid-token", "NewP@ssw0rd123!");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    @DisplayName("resetPassword: expired token should return 409 Conflict")
    @WithMockUser
    void resetPassword_ExpiredToken_ReturnsConflict() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("expired-token", "NewP@ssw0rd123!");

        doNothing().when(rateLimitService).checkRateLimit(eq("reset-password"), eq("127.0.0.1|expired-token"));
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid or expired reset token"))
                .when(authService).resetPassword("expired-token", "NewP@ssw0rd123!");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    @DisplayName("resetPassword: used token should return 409 Conflict")
    @WithMockUser
    void resetPassword_UsedToken_ReturnsConflict() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("used-token", "NewP@ssw0rd123!");

        doNothing().when(rateLimitService).checkRateLimit(eq("reset-password"), eq("127.0.0.1|used-token"));
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid or expired reset token"))
                .when(authService).resetPassword("used-token", "NewP@ssw0rd123!");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    @DisplayName("resetPassword: missing token should return 400 Bad Request")
    @WithMockUser
    void resetPassword_MissingToken_ReturnsBadRequest() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("", "NewP@ssw0rd123!");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("resetPassword: missing password should return 400 Bad Request")
    @WithMockUser
    void resetPassword_MissingPassword_ReturnsBadRequest() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("resetPassword: password too short should return 400 Bad Request")
    @WithMockUser
    void resetPassword_PasswordTooShort_ReturnsBadRequest() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "short");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("resetPassword: password too long should return 400 Bad Request")
    @WithMockUser
    void resetPassword_PasswordTooLong_ReturnsBadRequest() throws Exception {
        // Given
        String longPassword = "a".repeat(101); // 101 characters
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", longPassword);

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    @DisplayName("resetPassword: invalid JSON should return 400 Bad Request")
    @WithMockUser
    void resetPassword_InvalidJson_ReturnsBadRequest() throws Exception {
        // Given
        String invalidJson = "{ invalid json }";

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Malformed JSON"));
    }

    @Test
    @DisplayName("resetPassword: respects X-Forwarded-For header for rate limiting")
    @WithMockUser
    void resetPassword_RespectsXForwardedFor_ForRateLimiting() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "NewP@ssw0rd123!");

        doNothing().when(rateLimitService).checkRateLimit(eq("reset-password"), eq("192.168.1.1|valid-token"));
        doNothing().when(authService).resetPassword("valid-token", "NewP@ssw0rd123!");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "192.168.1.1, 10.0.0.1")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated successfully"));
    }

    @Test
    @DisplayName("resetPassword: handles multiple X-Forwarded-For values correctly")
    @WithMockUser
    void resetPassword_MultipleXForwardedFor_HandlesCorrectly() throws Exception {
        // Given
        ResetPasswordRequest request = new ResetPasswordRequest("valid-token", "NewP@ssw0rd123!");

        doNothing().when(rateLimitService).checkRateLimit(eq("reset-password"), eq("10.0.0.1|valid-token"));
        doNothing().when(authService).resetPassword("valid-token", "NewP@ssw0rd123!");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "192.168.1.1, 10.0.0.1, 172.16.0.1")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password updated successfully"));
    }
}
