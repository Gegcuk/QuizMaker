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
import uk.gegc.quizmaker.features.auth.api.dto.ResendVerificationRequest;
import uk.gegc.quizmaker.features.auth.api.dto.VerifyEmailRequest;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

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
        // Verify rate limit is checked with IP-only key
        verify(rateLimitService).checkRateLimit("verify-email", "127.0.0.1");
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
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(authService).verifyEmail("invalid-token");
        // Verify rate limit is checked even for invalid tokens
        verify(rateLimitService).checkRateLimit("verify-email", "127.0.0.1");
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - rate limit exceeded should return 429")
    @WithMockUser
    void verifyEmail_RateLimitExceeded_ShouldReturn429() throws Exception {
        // Given
        VerifyEmailRequest request = new VerifyEmailRequest("valid-token-here");
        when(trustedProxyUtil.getClientIp(any())).thenReturn("192.168.1.50");

        doThrow(new RateLimitExceededException("Rate limit exceeded", 45))
                .when(rateLimitService).checkRateLimit(eq("verify-email"), eq("192.168.1.50"));

        // When & Then
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());

        // Verify rate limit is checked with IP-only key (not token-based)
        verify(rateLimitService).checkRateLimit("verify-email", "192.168.1.50");
        verify(authService, never()).verifyEmail(anyString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - rate limit key should be IP-only")
    @WithMockUser
    void verifyEmail_RateLimitKeyIsIpOnly_ShouldPreventBypass() throws Exception {
        // Given
        String clientIp = "10.0.0.5";
        String token1 = "token-abc123";
        String token2 = "token-xyz789";
        LocalDateTime now = LocalDateTime.now();
        when(trustedProxyUtil.getClientIp(any())).thenReturn(clientIp);
        when(authService.verifyEmail(anyString())).thenReturn(now);

        // When - First request with token1
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new VerifyEmailRequest(token1))))
                .andExpect(status().isOk());

        // When - Second request with different token2 from same IP
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new VerifyEmailRequest(token2))))
                .andExpect(status().isOk());

        // Then - Both requests should use the same rate limit key (IP-only)
        // This ensures token rotation cannot bypass rate limits
        verify(rateLimitService, times(2)).checkRateLimit("verify-email", clientIp);
        // Verify no other rate limit calls were made with different keys
        verify(rateLimitService, times(2)).checkRateLimit(anyString(), anyString());
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
