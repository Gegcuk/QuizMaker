package uk.gegc.quizmaker.shared.email.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for content building functionality in AwsSesEmailService.
 * 
 * Tests cover:
 * - Password reset content URL encoding and formatting
 * - Email verification content URL encoding and formatting
 * - Base URL variations (trailing slash handling)
 * - TTL time description formatting for various minute values
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Content Builders Tests")
class AwsSesEmailServiceContentBuildersTest {

    @Mock
    private SesV2Client sesClient;
    
    @Mock
    private Resource passwordResetTemplateResource;
    
    @Mock
    private Resource verificationTemplateResource;
    
    private SimpleMeterRegistry meterRegistry;
    private AwsSesEmailService emailService;

    // Template content for testing - using simple format to verify URL and time insertion
    private static final String PASSWORD_RESET_TEMPLATE = "Reset URL: %s | Expires in: %s";
    private static final String VERIFICATION_TEMPLATE = "Verification URL: %s | Expires in: %s";

    @BeforeEach
    void setUp() throws IOException {
        meterRegistry = new SimpleMeterRegistry();
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        // Set up template resources
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(PASSWORD_RESET_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(VERIFICATION_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");
        
        injectTemplateResources();
        emailService.initialize();
    }

    private void injectTemplateResources() {
        try {
            var field = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
            field.setAccessible(true);
            field.set(emailService, passwordResetTemplateResource);
            
            field = AwsSesEmailService.class.getDeclaredField("verificationTemplateResource");
            field.setAccessible(true);
            field.set(emailService, verificationTemplateResource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject template resources", e);
        }
    }

    // ========== Password Reset Content Tests ==========

    @Test
    @DisplayName("Password reset content should include URL with token encoded via URLEncoder - handles special characters")
    void passwordResetContentShouldIncludeUrlWithEncodedToken() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        String tokenWithSpecialChars = "abc+def/ghi=jkl mno";
        String expectedEncodedToken = URLEncoder.encode(tokenWithSpecialChars, StandardCharsets.UTF_8);
        
        // Mock SES response
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", tokenWithSpecialChars);
        
        // Then - capture the request and verify URL encoding
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("http://localhost:3000/reset-password?token=" + expectedEncodedToken);
        assertThat(emailBody).contains("1 hour"); // 60 minutes
    }

    @Test
    @DisplayName("Password reset content should handle token with plus sign - encoded as %2B")
    void passwordResetContentShouldHandleTokenWithPlusSign() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        String tokenWithPlus = "token+with+plus";
        
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", tokenWithPlus);
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("token%2Bwith%2Bplus");
        assertThat(emailBody).doesNotContain("token+with+plus");
    }

    @Test
    @DisplayName("Password reset content should handle token with forward slash - encoded as %2F")
    void passwordResetContentShouldHandleTokenWithForwardSlash() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        String tokenWithSlash = "token/with/slash";
        
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", tokenWithSlash);
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("token%2Fwith%2Fslash");
        assertThat(emailBody).doesNotContain("token/with/slash");
    }

    @Test
    @DisplayName("Password reset content should handle token with equals sign - encoded as %3D")
    void passwordResetContentShouldHandleTokenWithEqualsSign() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        String tokenWithEquals = "token=with=equals";
        
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", tokenWithEquals);
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("token%3Dwith%3Dequals");
        assertThat(emailBody).doesNotContain("token=with=equals");
    }

    @Test
    @DisplayName("Password reset content should handle token with spaces - encoded as + (URLEncoder default)")
    void passwordResetContentShouldHandleTokenWithSpaces() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        String tokenWithSpaces = "token with spaces";
        
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", tokenWithSpaces);
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        // URLEncoder.encode() encodes spaces as + (application/x-www-form-urlencoded format)
        assertThat(emailBody).contains("token+with+spaces");
        assertThat(emailBody).doesNotContain("token with spaces");
    }

    @Test
    @DisplayName("Email verification content should include URL with token encoded via URLEncoder")
    void emailVerificationContentShouldIncludeUrlWithEncodedToken() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        String tokenWithSpecialChars = "verify+test/token=123";
        String expectedEncodedToken = URLEncoder.encode(tokenWithSpecialChars, StandardCharsets.UTF_8);
        
        mockSesResponse();
        
        // When
        emailService.sendEmailVerificationEmail("test@example.com", tokenWithSpecialChars);
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("http://localhost:3000/verify-email?token=" + expectedEncodedToken);
        assertThat(emailBody).contains("2 hours"); // 120 minutes
    }

    @Test
    @DisplayName("Email verification content should use correct path - verify-email not reset-password")
    void emailVerificationContentShouldUseCorrectPath() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendEmailVerificationEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("/verify-email?token=");
        assertThat(emailBody).doesNotContain("/reset-password");
    }

    @Test
    @DisplayName("Base URL with trailing slash should produce valid links - double slash acceptable")
    void baseUrlWithTrailingSlashShouldProduceValidLinks() {
        // Given
        injectProperties("http://localhost:3000/", 60L, 120L); // Trailing slash
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        // Note: current implementation produces double slash, which is acceptable but noted for future hardening
        assertThat(emailBody).contains("http://localhost:3000//reset-password?token=");
    }

    @Test
    @DisplayName("Base URL without trailing slash should produce valid links")
    void baseUrlWithoutTrailingSlashShouldProduceValidLinks() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L); // No trailing slash
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("http://localhost:3000/reset-password?token=");
        assertThat(emailBody).doesNotContain("//reset-password"); // Single slash
    }

    @Test
    @DisplayName("Verification URL with base URL trailing slash should produce valid links")
    void verificationUrlWithBaseUrlTrailingSlashShouldProduceValidLinks() {
        // Given
        injectProperties("http://example.com/", 60L, 120L); // Trailing slash
        mockSesResponse();
        
        // When
        emailService.sendEmailVerificationEmail("test@example.com", "verify-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("http://example.com//verify-email?token=");
    }

    @Test
    @DisplayName("formatTimeDescription - 1 minute should return '1 minutes' (current behavior)")
    void formatTimeDescription1MinuteShouldReturn1Minutes() {
        // Given
        injectProperties("http://localhost:3000", 1L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("1 minutes"); // Note: current behavior, not "1 minute"
    }

    @Test
    @DisplayName("formatTimeDescription - less than 60 minutes should return 'X minutes'")
    void formatTimeDescriptionLessThan60MinutesShouldReturnMinutes() {
        // Given
        injectProperties("http://localhost:3000", 45L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("45 minutes");
    }

    @Test
    @DisplayName("formatTimeDescription - exactly 60 minutes should return '1 hour'")
    void formatTimeDescription60MinutesShouldReturn1Hour() {
        // Given
        injectProperties("http://localhost:3000", 60L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("1 hour");
        assertThat(emailBody).doesNotContain("60 minutes");
    }

    @Test
    @DisplayName("formatTimeDescription - exactly 120 minutes should return '2 hours'")
    void formatTimeDescription120MinutesShouldReturn2Hours() {
        // Given
        injectProperties("http://localhost:3000", 120L, 60L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("2 hours");
        assertThat(emailBody).doesNotContain("120 minutes");
        assertThat(emailBody).doesNotContain(" and "); // No remaining minutes
    }

    @Test
    @DisplayName("formatTimeDescription - 135 minutes should return '2 hours and 15 minutes'")
    void formatTimeDescription135MinutesShouldReturn2HoursAnd15Minutes() {
        // Given
        injectProperties("http://localhost:3000", 135L, 60L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("2 hours and 15 minutes");
    }

    @Test
    @DisplayName("formatTimeDescription - 0 minutes should return '0 minutes' (edge case)")
    void formatTimeDescription0MinutesShouldReturn0Minutes() {
        // Given
        injectProperties("http://localhost:3000", 0L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("0 minutes");
    }

    @Test
    @DisplayName("formatTimeDescription - negative minutes should return '-N minutes' (current behavior)")
    void formatTimeDescriptionNegativeMinutesShouldReturnNegativeMinutes() {
        // Given
        injectProperties("http://localhost:3000", -10L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("-10 minutes");
    }

    @Test
    @DisplayName("formatTimeDescription - 61 minutes should return '1 hour and 1 minutes'")
    void formatTimeDescription61MinutesShouldReturn1HourAnd1Minutes() {
        // Given
        injectProperties("http://localhost:3000", 61L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("1 hour and 1 minutes");
    }

    @Test
    @DisplayName("formatTimeDescription - 180 minutes should return '3 hours'")
    void formatTimeDescription180MinutesShouldReturn3Hours() {
        // Given
        injectProperties("http://localhost:3000", 180L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("3 hours");
        assertThat(emailBody).doesNotContain(" and ");
    }

    @Test
    @DisplayName("Email verification should use verification token TTL not password reset TTL")
    void emailVerificationShouldUseVerificationTokenTtl() {
        // Given - different TTLs for each type
        injectProperties("http://localhost:3000", 25L, 90L); // 25 min reset, 90 min verification
        mockSesResponse();
        
        // When
        emailService.sendEmailVerificationEmail("test@example.com", "verify-token");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("1 hour and 30 minutes"); // 90 minutes
        assertThat(emailBody).doesNotContain("25 minutes"); // Should not use password reset TTL
    }

    @Test
    @DisplayName("Password reset URL should use /reset-password path")
    void passwordResetUrlShouldUseCorrectPath() {
        // Given
        injectProperties("https://app.example.com", 60L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@test.com", "reset-token-123");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("https://app.example.com/reset-password?token=reset-token-123");
    }

    @Test
    @DisplayName("Verification URL should use /verify-email path")
    void verificationUrlShouldUseCorrectPath() {
        // Given
        injectProperties("https://app.example.com", 60L, 120L);
        mockSesResponse();
        
        // When
        emailService.sendEmailVerificationEmail("user@test.com", "verify-token-456");
        
        // Then
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        String emailBody = requestCaptor.getValue().content().simple().body().text().data();
        assertThat(emailBody).contains("https://app.example.com/verify-email?token=verify-token-456");
    }

    private void injectProperties(String baseUrl, long resetTtlMinutes, long verificationTtlMinutes) {
        try {
            var fromEmailField = AwsSesEmailService.class.getDeclaredField("fromEmail");
            fromEmailField.setAccessible(true);
            fromEmailField.set(emailService, "noreply@example.com");
            
            var baseUrlField = AwsSesEmailService.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            baseUrlField.set(emailService, baseUrl);
            
            var passwordResetSubjectField = AwsSesEmailService.class.getDeclaredField("passwordResetSubject");
            passwordResetSubjectField.setAccessible(true);
            passwordResetSubjectField.set(emailService, "Reset Your Password");
            
            var verificationSubjectField = AwsSesEmailService.class.getDeclaredField("verificationSubject");
            verificationSubjectField.setAccessible(true);
            verificationSubjectField.set(emailService, "Verify Your Email");
            
            var resetTokenTtlField = AwsSesEmailService.class.getDeclaredField("resetTokenTtlMinutes");
            resetTokenTtlField.setAccessible(true);
            resetTokenTtlField.set(emailService, resetTtlMinutes);
            
            var verificationTokenTtlField = AwsSesEmailService.class.getDeclaredField("verificationTokenTtlMinutes");
            verificationTokenTtlField.setAccessible(true);
            verificationTokenTtlField.set(emailService, verificationTtlMinutes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject properties", e);
        }
    }

    private void mockSesResponse() {
        var mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id-" + System.nanoTime())
            .build();
        lenient().when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);
    }
}

