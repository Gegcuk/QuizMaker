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
import uk.gegc.quizmaker.dto.auth.ResendVerificationRequest;
import uk.gegc.quizmaker.dto.auth.VerifyEmailRequest;
import uk.gegc.quizmaker.exception.RateLimitExceededException;
import uk.gegc.quizmaker.service.RateLimitService;
import uk.gegc.quizmaker.service.auth.AuthService;
import uk.gegc.quizmaker.util.TrustedProxyUtil;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@TestPropertySource(properties = {
    "app.auth.verification-token-pepper=test-pepper",
    "app.auth.verification-token-ttl-minutes=1440"
})
@DisplayName("AuthController Email Verification Tests")
class AuthControllerEmailVerificationTest {

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
    @DisplayName("POST /api/v1/auth/verify-email - valid token should return 200")
    @WithMockUser
    void verifyEmail_ValidToken_ShouldReturn200() throws Exception {
        // Given
        VerifyEmailRequest request = new VerifyEmailRequest("valid-token-here");
        LocalDateTime now = LocalDateTime.now();
        when(authService.verifyEmail(anyString())).thenReturn(now);
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.message").value("Email verified successfully"))
                .andExpect(jsonPath("$.verifiedAt").exists());

        verify(authService).verifyEmail("valid-token-here");
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - blank token should return 400")
    @WithMockUser
    void verifyEmail_BlankToken_ShouldReturn400() throws Exception {
        // Given
        VerifyEmailRequest request = new VerifyEmailRequest("");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(authService, never()).verifyEmail(anyString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - oversized token should return 400")
    @WithMockUser
    void verifyEmail_OversizedToken_ShouldReturn400() throws Exception {
        // Given
        String oversizedToken = "a".repeat(513); // Exceeds 512 character limit
        VerifyEmailRequest request = new VerifyEmailRequest(oversizedToken);

        // When & Then
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(authService, never()).verifyEmail(anyString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - invalid token should return 400")
    @WithMockUser
    void verifyEmail_InvalidToken_ShouldReturn400() throws Exception {
        // Given
        VerifyEmailRequest request = new VerifyEmailRequest("invalid-token");
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token"))
                .when(authService).verifyEmail(anyString());

        // When & Then
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(authService).verifyEmail("invalid-token");
    }

    @Test
    @DisplayName("POST /api/v1/auth/resend-verification - valid email should return 202")
    @WithMockUser
    void resendVerification_ValidEmail_ShouldReturn202() throws Exception {
        // Given
        ResendVerificationRequest request = new ResendVerificationRequest("test@example.com");
        doNothing().when(rateLimitService).checkRateLimit(anyString(), anyString());
        doNothing().when(authService).generateEmailVerificationToken(anyString());
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If the email exists and is not verified, a verification link was sent."));

        verify(rateLimitService).checkRateLimit("resend-verification", "test@example.com|127.0.0.1");
        verify(authService).generateEmailVerificationToken("test@example.com");
    }

    @Test
    @DisplayName("POST /api/v1/auth/resend-verification - invalid email should return 400")
    @WithMockUser
    void resendVerification_InvalidEmail_ShouldReturn400() throws Exception {
        // Given
        ResendVerificationRequest request = new ResendVerificationRequest("invalid-email");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString());
        verify(authService, never()).generateEmailVerificationToken(anyString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/resend-verification - rate limit exceeded should return 429")
    @WithMockUser
    void resendVerification_RateLimitExceeded_ShouldReturn429() throws Exception {
        // Given
        ResendVerificationRequest request = new ResendVerificationRequest("test@example.com");
        doThrow(new RateLimitExceededException("Rate limit exceeded"))
                .when(rateLimitService).checkRateLimit(anyString(), anyString());
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());

        verify(rateLimitService).checkRateLimit("resend-verification", "test@example.com|127.0.0.1");
        verify(authService, never()).generateEmailVerificationToken(anyString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/resend-verification - blank email should return 400")
    @WithMockUser
    void resendVerification_BlankEmail_ShouldReturn400() throws Exception {
        // Given
        ResendVerificationRequest request = new ResendVerificationRequest("");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(rateLimitService, never()).checkRateLimit(anyString(), anyString());
        verify(authService, never()).generateEmailVerificationToken(anyString());
    }
}
