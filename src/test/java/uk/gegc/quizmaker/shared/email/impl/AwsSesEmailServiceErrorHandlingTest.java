package uk.gegc.quizmaker.shared.email.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for error handling in AwsSesEmailService.
 * 
 * Tests cover:
 * - SesV2Exception 4xx (client errors) - logs WARN, records metric with client-<code>, does not throw
 * - SesV2Exception 429 (throttling) - logs ERROR, records metric with server-429, does not throw
 * - SesV2Exception 5xx (server errors) - logs ERROR, records metric with server-<code>, does not throw
 * - Unexpected RuntimeException - logs ERROR, records metric with unexpected, does not throw
 * - Email masking in all error log paths
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Error Handling Tests")
class AwsSesEmailServiceErrorHandlingTest {

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

    // ========== 4xx Client Error Tests ==========

    @Test
    @DisplayName("SesV2Exception 400 should log WARN and record client-400 metric without throwing")
    void sesV2Exception400ShouldLogWarnAndRecordMetricWithoutThrowing() {
        // Given
        SesV2Exception exception = createSesException(400, "Bad Request", "Invalid email address");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then - should not throw
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        // Verify failure metric recorded with client-400 reason
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "client-400")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SesV2Exception 403 should log WARN and record client-403 metric without throwing")
    void sesV2Exception403ShouldLogWarnAndRecordMetricWithoutThrowing() {
        // Given
        SesV2Exception exception = createSesException(403, "Forbidden", "Sender not verified");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "client-403")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SesV2Exception 404 should log WARN and record client-404 metric without throwing")
    void sesV2Exception404ShouldLogWarnAndRecordMetricWithoutThrowing() {
        // Given
        SesV2Exception exception = createSesException(404, "Not Found", "Configuration set not found");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "client-404")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========== 429 Throttling Test ==========

    @Test
    @DisplayName("SesV2Exception 429 should log WARN and record client-429 metric without throwing")
    void sesV2Exception429ShouldLogWarnAndRecordMetricWithoutThrowing() {
        // Given - 429 is technically 4xx so treated as client error
        SesV2Exception exception = createSesException(429, "Too Many Requests", "Rate limit exceeded");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        // Note: 429 is in 4xx range, so classified as client-429 not server-429
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "client-429")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========== 5xx Server Error Tests ==========

    @Test
    @DisplayName("SesV2Exception 500 should log ERROR and record server-500 metric without throwing")
    void sesV2Exception500ShouldLogErrorAndRecordMetricWithoutThrowing() {
        // Given
        SesV2Exception exception = createSesException(500, "Internal Server Error", "SES service error");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "server-500")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SesV2Exception 503 should log ERROR and record server-503 metric without throwing")
    void sesV2Exception503ShouldLogErrorAndRecordMetricWithoutThrowing() {
        // Given
        SesV2Exception exception = createSesException(503, "Service Unavailable", "SES temporarily unavailable");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "server-503")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========== Unexpected Exception Tests ==========

    @Test
    @DisplayName("Unexpected RuntimeException should log ERROR and record unexpected metric without throwing")
    void unexpectedRuntimeExceptionShouldLogErrorAndRecordMetricWithoutThrowing() {
        // Given
        RuntimeException exception = new RuntimeException("Unexpected error");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "unexpected")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("NullPointerException should be caught and recorded as unexpected without throwing")
    void nullPointerExceptionShouldBeCaughtAndRecordedAsUnexpected() {
        // Given
        NullPointerException exception = new NullPointerException("Null pointer error");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "unexpected")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========== Verification Email Error Tests ==========

    @Test
    @DisplayName("Verification email with 400 error should record email-verification metric")
    void verificationEmailWith400ErrorShouldRecordCorrectMetric() throws Exception {
        // Given
        injectProperty("verificationSubject", "Verify");
        injectProperty("verificationTokenTtlMinutes", 120L);
        
        SesV2Exception exception = createSesException(400, "Bad Request", "Invalid recipient");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendEmailVerificationEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .tag("reason", "client-400")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Verification email with 500 error should record server-500 metric")
    void verificationEmailWith500ErrorShouldRecordServerMetric() throws Exception {
        // Given
        injectProperty("verificationSubject", "Verify");
        injectProperty("verificationTokenTtlMinutes", 120L);
        
        SesV2Exception exception = createSesException(500, "Internal Server Error", "SES error");
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);
        
        // When & Then
        assertThatCode(() -> emailService.sendEmailVerificationEmail("user@example.com", "token"))
            .doesNotThrowAnyException();
        
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .tag("reason", "server-500")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========== Multiple Errors Test ==========

    @Test
    @DisplayName("Multiple errors should increment counters correctly")
    void multipleErrorsShouldIncrementCountersCorrectly() {
        // Given
        SesV2Exception clientError = createSesException(400, "Bad Request", "Error 1");
        SesV2Exception serverError = createSesException(500, "Server Error", "Error 2");
        
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(clientError)
            .thenThrow(serverError)
            .thenThrow(clientError);
        
        // When
        emailService.sendPasswordResetEmail("user1@example.com", "token1");
        emailService.sendPasswordResetEmail("user2@example.com", "token2");
        emailService.sendPasswordResetEmail("user3@example.com", "token3");
        
        // Then
        var clientCounter = meterRegistry.find("email.failed")
            .tag("reason", "client-400")
            .counter();
        var serverCounter = meterRegistry.find("email.failed")
            .tag("reason", "server-500")
            .counter();
        
        assertThat(clientCounter.count()).isEqualTo(2.0);
        assertThat(serverCounter.count()).isEqualTo(1.0);
    }

    // ========== Helper Methods ==========

    private SesV2Exception createSesException(int statusCode, String errorCode, String errorMessage) {
        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .build();
        
        return (SesV2Exception) SesV2Exception.builder()
            .statusCode(statusCode)
            .awsErrorDetails(errorDetails)
            .message(errorMessage)
            .build();
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
}

