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
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for metrics recording in AwsSesEmailService.
 * 
 * Tests cover:
 * - Success counters increment: email.sent{provider=ses,type=password-reset} and email.sent{provider=ses,type=email-verification}
 * - Failure counters increment with type and reason tags
 * - Null-safe behavior when MeterRegistry is absent
 * - Reason sanitization (lowercase, null handling)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Metrics Tests")
class AwsSesEmailServiceMetricsTest {

    @Mock
    private SesV2Client sesClient;
    @Mock
    private Resource passwordResetTemplateResource;
    @Mock
    private Resource verificationTemplateResource;
    
    private SimpleMeterRegistry meterRegistry;
    private AwsSesEmailService emailService;

    private static final String PASSWORD_RESET_TEMPLATE = "Reset: %s | Expires: %s";
    private static final String VERIFICATION_TEMPLATE = "Verify: %s | Expires: %s";

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(PASSWORD_RESET_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(VERIFICATION_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");
        
        injectTemplateResources();
        emailService.initialize();
        setupBasicProperties();
    }

    private void injectTemplateResources() throws Exception {
        var field = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
        field.setAccessible(true);
        field.set(emailService, passwordResetTemplateResource);
        
        field = AwsSesEmailService.class.getDeclaredField("verificationTemplateResource");
        field.setAccessible(true);
        field.set(emailService, verificationTemplateResource);
    }

    private void setupBasicProperties() throws Exception {
        injectProperty("fromEmail", "from@test.com");
        injectProperty("baseUrl", "http://localhost:3000");
        injectProperty("passwordResetSubject", "Reset Password");
        injectProperty("verificationSubject", "Verify Email");
        injectProperty("resetTokenTtlMinutes", 60L);
        injectProperty("verificationTokenTtlMinutes", 120L);
    }

    private void injectProperty(String fieldName, Object value) throws Exception {
        var field = AwsSesEmailService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(emailService, value);
    }

    // ========== Success Metrics Tests ==========

    @Test
    @DisplayName("Password reset success should increment email.sent{provider=ses,type=password-reset}")
    void passwordResetSuccessShouldIncrementSuccessMetric() {
        // Given
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(mockResponse);
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Email verification success should increment email.sent{provider=ses,type=email-verification}")
    void emailVerificationSuccessShouldIncrementSuccessMetric() throws Exception {
        // Given
        injectProperty("verificationSubject", "Verify Email");
        injectProperty("verificationTokenTtlMinutes", 120L);
        
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(mockResponse);
        
        // When
        emailService.sendEmailVerificationEmail("user@example.com", "token");
        
        // Then
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Multiple successful sends should increment counters correctly")
    void multipleSuccessfulSendsShouldIncrementCountersCorrectly() throws Exception {
        // Given
        injectProperty("verificationSubject", "Verify Email");
        injectProperty("verificationTokenTtlMinutes", 120L);
        
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(mockResponse);
        
        // When
        emailService.sendPasswordResetEmail("user1@example.com", "token1");
        emailService.sendPasswordResetEmail("user2@example.com", "token2");
        emailService.sendEmailVerificationEmail("user3@example.com", "token3");
        emailService.sendEmailVerificationEmail("user4@example.com", "token4");
        emailService.sendEmailVerificationEmail("user5@example.com", "token5");
        
        // Then
        var passwordResetCounter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .counter();
        
        var verificationCounter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .counter();
        
        assertThat(passwordResetCounter.count()).isEqualTo(2.0);
        assertThat(verificationCounter.count()).isEqualTo(3.0);
    }

    // ========== Failure Metrics Tests ==========

    @Test
    @DisplayName("Password reset failure should increment email.failed with correct tags")
    void passwordResetFailureShouldIncrementFailureMetric() {
        // Given
        software.amazon.awssdk.services.sesv2.model.SesV2Exception exception = 
            createSesException(400, "Bad Request", "Invalid email");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "client-400")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Email verification failure should increment email.failed with correct tags")
    void emailVerificationFailureShouldIncrementFailureMetric() throws Exception {
        // Given
        injectProperty("verificationSubject", "Verify Email");
        injectProperty("verificationTokenTtlMinutes", 120L);
        
        software.amazon.awssdk.services.sesv2.model.SesV2Exception exception = 
            createSesException(500, "Internal Server Error", "SES error");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When
        emailService.sendEmailVerificationEmail("user@example.com", "token");
        
        // Then
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .tag("reason", "server-500")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Unexpected exception should increment email.failed with unexpected reason")
    void unexpectedExceptionShouldIncrementFailureMetricWithUnexpectedReason() {
        // Given
        RuntimeException exception = new RuntimeException("Unexpected error");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "unexpected")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Multiple failures should increment counters correctly")
    void multipleFailuresShouldIncrementCountersCorrectly() throws Exception {
        // Given
        injectProperty("verificationSubject", "Verify Email");
        injectProperty("verificationTokenTtlMinutes", 120L);
        
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(createSesException(400, "Bad Request", "Error 1"))  // client-400
            .thenThrow(createSesException(500, "Server Error", "Error 2")) // server-500
            .thenThrow(new RuntimeException("Unexpected"));                // unexpected
        
        // When
        emailService.sendPasswordResetEmail("user1@example.com", "token1");
        emailService.sendEmailVerificationEmail("user2@example.com", "token2");
        emailService.sendPasswordResetEmail("user3@example.com", "token3");
        
        // Then
        var clientCounter = meterRegistry.find("email.failed")
            .tag("reason", "client-400")
            .counter();
        var serverCounter = meterRegistry.find("email.failed")
            .tag("reason", "server-500")
            .counter();
        var unexpectedCounter = meterRegistry.find("email.failed")
            .tag("reason", "unexpected")
            .counter();
        
        assertThat(clientCounter.count()).isEqualTo(1.0);
        assertThat(serverCounter.count()).isEqualTo(1.0);
        assertThat(unexpectedCounter.count()).isEqualTo(1.0);
    }

    // ========== Null Safety Tests ==========

    @Test
    @DisplayName("Should handle null MeterRegistry gracefully - no NPE on success")
    void shouldHandleNullMeterRegistryGracefullyOnSuccess() throws Exception {
        // Given - inject null meter registry
        var field = AwsSesEmailService.class.getDeclaredField("meterRegistry");
        field.setAccessible(true);
        field.set(emailService, null);
        
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(mockResponse);
        
        // When & Then - should not throw NPE
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Verify email was still sent
        verify(sesClient).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    @DisplayName("Should handle null MeterRegistry gracefully - no NPE on failure")
    void shouldHandleNullMeterRegistryGracefullyOnFailure() throws Exception {
        // Given - inject null meter registry
        var field = AwsSesEmailService.class.getDeclaredField("meterRegistry");
        field.setAccessible(true);
        field.set(emailService, null);
        
        software.amazon.awssdk.services.sesv2.model.SesV2Exception exception = 
            createSesException(400, "Bad Request", "Invalid email");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then - should not throw NPE
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Verify SES client was called (exception handling worked)
        verify(sesClient).sendEmail(any(SendEmailRequest.class));
    }

    // ========== Reason Sanitization Tests ==========

    @Test
    @DisplayName("Should sanitize reason tags to lowercase")
    void shouldSanitizeReasonTagsToLowercase() throws Exception {
        // Given - create a custom exception that would produce uppercase reason
        // We'll test this by directly calling recordFailure through reflection
        var method = AwsSesEmailService.class.getDeclaredMethod("recordFailure", String.class, String.class);
        method.setAccessible(true);
        
        // When
        method.invoke(emailService, "password-reset", "CLIENT-400");
        
        // Then
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "client-400")  // Should be lowercase
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should handle null reason gracefully")
    void shouldHandleNullReasonGracefully() throws Exception {
        // Given - test null reason handling
        var method = AwsSesEmailService.class.getDeclaredMethod("recordFailure", String.class, String.class);
        method.setAccessible(true);
        
        // When
        method.invoke(emailService, "password-reset", null);
        
        // Then
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "unknown")  // Should default to "unknown"
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========== Helper Methods ==========

    private software.amazon.awssdk.services.sesv2.model.SesV2Exception createSesException(int statusCode, String errorCode, String errorMessage) {
        software.amazon.awssdk.awscore.exception.AwsErrorDetails errorDetails = 
            software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
        
        return (software.amazon.awssdk.services.sesv2.model.SesV2Exception) 
            software.amazon.awssdk.services.sesv2.model.SesV2Exception.builder()
                .statusCode(statusCode)
                .awsErrorDetails(errorDetails)
                .message(errorMessage)
                .build();
    }
}
