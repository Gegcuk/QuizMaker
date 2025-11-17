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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * Helper method to calculate token fingerprint (matches AuthController.fingerprintToken implementation)
     */
    private String fingerprintToken(String token) {
        if (token == null || token.isBlank()) {
            return "missing";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

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
        // Verify rate limit is checked with IP + token fingerprint key
        String expectedKey = "127.0.0.1|" + fingerprintToken("valid-token-here");
        verify(rateLimitService).checkRateLimit("verify-email", expectedKey);
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
        // Verify rate limit is checked even for invalid tokens (with IP + token fingerprint)
        String expectedKey = "127.0.0.1|" + fingerprintToken("invalid-token");
        verify(rateLimitService).checkRateLimit("verify-email", expectedKey);
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - rate limit exceeded should return 429")
    @WithMockUser
    void verifyEmail_RateLimitExceeded_ShouldReturn429() throws Exception {
        // Given
        String token = "valid-token-here";
        VerifyEmailRequest request = new VerifyEmailRequest(token);
        String clientIp = "192.168.1.50";
        when(trustedProxyUtil.getClientIp(any())).thenReturn(clientIp);

        String expectedKey = clientIp + "|" + fingerprintToken(token);
        doThrow(new RateLimitExceededException("Rate limit exceeded", 45))
                .when(rateLimitService).checkRateLimit(eq("verify-email"), eq(expectedKey));

        // When & Then
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests());

        // Verify rate limit is checked with IP + token fingerprint key
        verify(rateLimitService).checkRateLimit("verify-email", expectedKey);
        verify(authService, never()).verifyEmail(anyString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - rate limit key combines IP and token fingerprint to prevent shared IP throttling")
    @WithMockUser
    void verifyEmail_RateLimitKeyCombinesIpAndTokenFingerprint_ShouldPreventSharedIpThrottling() throws Exception {
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

        // Then - Each request should use a different rate limit key (IP + token fingerprint)
        // This ensures users behind shared IPs with different tokens don't throttle each other
        String expectedKey1 = clientIp + "|" + fingerprintToken(token1);
        String expectedKey2 = clientIp + "|" + fingerprintToken(token2);
        verify(rateLimitService).checkRateLimit("verify-email", expectedKey1);
        verify(rateLimitService).checkRateLimit("verify-email", expectedKey2);
        // Verify exactly 2 rate limit calls were made
        verify(rateLimitService, times(2)).checkRateLimit(eq("verify-email"), anyString());
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - same token from same IP should use same rate limit key to prevent brute force")
    @WithMockUser
    void verifyEmail_SameTokenFromSameIp_ShouldUseSameRateLimitKey() throws Exception {
        // Given
        String clientIp = "192.168.1.100";
        String token = "same-token-123";
        LocalDateTime now = LocalDateTime.now();
        when(trustedProxyUtil.getClientIp(any())).thenReturn(clientIp);
        when(authService.verifyEmail(anyString())).thenReturn(now);

        // When - First request
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new VerifyEmailRequest(token))))
                .andExpect(status().isOk());

        // When - Second request with same token from same IP
        mockMvc.perform(post("/api/v1/auth/verify-email")
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new VerifyEmailRequest(token))))
                .andExpect(status().isOk());

        // Then - Both requests should use the same rate limit key (IP + same token fingerprint)
        // This ensures brute-force attempts on the same token are rate-limited
        String expectedKey = clientIp + "|" + fingerprintToken(token);
        verify(rateLimitService, times(2)).checkRateLimit("verify-email", expectedKey);
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
