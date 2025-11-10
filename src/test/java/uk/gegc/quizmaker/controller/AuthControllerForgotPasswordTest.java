package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.auth.api.dto.ForgotPasswordRequest;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create"
})
@DisplayName("Forgot Password Tests - AuthController")
public class AuthControllerForgotPasswordTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("POST /api/v1/auth/forgot-password with valid email → returns 202 ACCEPTED")
    void forgotPassword_ValidEmail_ReturnsAccepted() throws Exception {
        // Given
        ForgotPasswordRequest request = new ForgotPasswordRequest("test@example.com");
        
        doNothing().when(rateLimitService).checkRateLimit(eq("forgot-password"), eq("test@example.com|127.0.0.1"));
        doNothing().when(authService).generatePasswordResetToken("test@example.com");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("If the email exists, a reset link was sent."));
    }

    @Test
    @DisplayName("POST /api/v1/auth/forgot-password with invalid email → returns 400 BAD_REQUEST")
    void forgotPassword_InvalidEmail_ReturnsBadRequest() throws Exception {
        // Given
        ForgotPasswordRequest request = new ForgotPasswordRequest("invalid-email");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/forgot-password with rate limit exceeded → returns 429 TOO_MANY_REQUESTS")
    void forgotPassword_RateLimitExceeded_ReturnsTooManyRequests() throws Exception {
        // Given
        ForgotPasswordRequest request = new ForgotPasswordRequest("test@example.com");
        
        doThrow(new RateLimitExceededException("Too many requests for forgot-password", 45))
                .when(rateLimitService).checkRateLimit(eq("forgot-password"), eq("test@example.com|127.0.0.1"));

        // When & Then
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/rate-limit-exceeded"))
                .andExpect(jsonPath("$.title").value("Rate Limit Exceeded"))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.retryAfterSeconds").value(45))
                .andExpect(header().string("Retry-After", "45"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/forgot-password with empty email → returns 400 BAD_REQUEST")
    void forgotPassword_EmptyEmail_ReturnsBadRequest() throws Exception {
        // Given
        ForgotPasswordRequest request = new ForgotPasswordRequest("");

        // When & Then
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/forgot-password with null email → returns 400 BAD_REQUEST")
    void forgotPassword_NullEmail_ReturnsBadRequest() throws Exception {
        // Given
        ForgotPasswordRequest request = new ForgotPasswordRequest(null);

        // When & Then
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
