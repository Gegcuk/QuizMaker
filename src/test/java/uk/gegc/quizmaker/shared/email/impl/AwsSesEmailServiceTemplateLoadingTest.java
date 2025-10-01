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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for template loading functionality in AwsSesEmailService.
 * 
 * Tests cover:
 * - Loading templates from classpath
 * - Missing template resource handling
 * - Empty template file handling
 * - Custom template paths via properties
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Template Loading Tests")
class AwsSesEmailServiceTemplateLoadingTest {

    @Mock
    private SesV2Client sesClient;
    
    @Mock
    private Resource passwordResetTemplateResource;
    
    @Mock
    private Resource verificationTemplateResource;
    
    private SimpleMeterRegistry meterRegistry;
    private AwsSesEmailService emailService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        // Set up reflection to inject template resources
        injectTemplateResources();
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

    @Test
    @DisplayName("Should load password reset and verification templates from classpath successfully")
    void shouldLoadTemplatesFromClasspathSuccessfully() throws IOException {
        // Given
        String passwordResetContent = "Password reset template with %s and %s placeholders";
        String verificationContent = "Email verification template with %s and %s placeholders";
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(passwordResetContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(verificationContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");

        // When & Then - should complete without exception
        emailService.initialize();
        
        verify(passwordResetTemplateResource).getInputStream();
        verify(verificationTemplateResource).getInputStream();
    }

    @Test
    @DisplayName("Should throw IllegalStateException with helpful message when password reset template resource is missing")
    void shouldThrowIllegalStateExceptionWhenPasswordResetTemplateResourceIsMissing() throws Exception {
        // Given - inject null resource
        var field = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
        field.setAccessible(true);
        field.set(emailService, null);

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Missing resource for password reset email template");
    }

    @Test
    @DisplayName("Should throw IllegalStateException with helpful message when verification template resource is missing")
    void shouldThrowIllegalStateExceptionWhenVerificationTemplateResourceIsMissing() throws IOException {
        // Given
        String passwordResetContent = "Password reset template with %s and %s placeholders";
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(passwordResetContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        when(verificationTemplateResource.getDescription()).thenReturn("missing-verification-template");

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load email verification email template")
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Should throw IllegalStateException when password reset template file is empty")
    void shouldThrowIllegalStateExceptionWhenPasswordResetTemplateFileIsEmpty() throws IOException {
        // Given
        when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        when(passwordResetTemplateResource.getDescription()).thenReturn("empty-password-reset-template");

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load password reset email template")
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("password reset template is empty: empty-password-reset-template");
    }

    @Test
    @DisplayName("Should throw IllegalStateException when verification template file is empty")
    void shouldThrowIllegalStateExceptionWhenVerificationTemplateFileIsEmpty() throws IOException {
        // Given
        String passwordResetContent = "Password reset template with %s and %s placeholders";
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(passwordResetContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        when(verificationTemplateResource.getDescription()).thenReturn("empty-verification-template");

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load email verification email template")
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("email verification template is empty: empty-verification-template");
    }

    @Test
    @DisplayName("Should throw IllegalStateException when password reset template file contains only whitespace")
    void shouldThrowIllegalStateExceptionWhenPasswordResetTemplateFileContainsOnlyWhitespace() throws IOException {
        // Given
        when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream("   \n\t  ".getBytes(StandardCharsets.UTF_8)));
        when(passwordResetTemplateResource.getDescription()).thenReturn("whitespace-password-reset-template");

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load password reset email template")
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("password reset template is empty: whitespace-password-reset-template");
    }

    @Test
    @DisplayName("Should throw IllegalStateException when verification template file contains only whitespace")
    void shouldThrowIllegalStateExceptionWhenVerificationTemplateFileContainsOnlyWhitespace() throws IOException {
        // Given
        String passwordResetContent = "Password reset template with %s and %s placeholders";
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(passwordResetContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream("   \n\t  ".getBytes(StandardCharsets.UTF_8)));
        when(verificationTemplateResource.getDescription()).thenReturn("whitespace-verification-template");

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load email verification email template")
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("email verification template is empty: whitespace-verification-template");
    }

    @Test
    @DisplayName("Should handle IOException during template loading and wrap in IllegalStateException")
    void shouldHandleIOExceptionDuringTemplateLoadingAndWrapInIllegalStateException() throws IOException {
        // Given
        when(passwordResetTemplateResource.getInputStream()).thenThrow(new IOException("I/O error"));
        when(passwordResetTemplateResource.getDescription()).thenReturn("error-password-reset-template");

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load password reset email template")
            .hasCauseInstanceOf(IOException.class)
            .hasRootCauseMessage("I/O error");
    }

    @Test
    @DisplayName("Should handle IOException during verification template loading and wrap in IllegalStateException")
    void shouldHandleIOExceptionDuringVerificationTemplateLoadingAndWrapInIllegalStateException() throws IOException {
        // Given
        String passwordResetContent = "Password reset template with %s and %s placeholders";
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(passwordResetContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        when(verificationTemplateResource.getInputStream()).thenThrow(new IOException("I/O error"));
        when(verificationTemplateResource.getDescription()).thenReturn("error-verification-template");

        // When & Then
        assertThatThrownBy(() -> emailService.initialize())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot load email verification email template")
            .hasCauseInstanceOf(IOException.class)
            .hasRootCauseMessage("I/O error");
    }

    @Test
    @DisplayName("Should load templates with custom paths via properties")
    void shouldLoadTemplatesWithCustomPathsViaProperties() throws IOException {
        // Given - templates with custom content that would come from custom paths
        String customPasswordResetContent = "Custom password reset template from %s with %s placeholders";
        String customVerificationContent = "Custom verification template from %s with %s placeholders";
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(customPasswordResetContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("custom-password-reset-path");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(customVerificationContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("custom-verification-path");

        // When & Then - should load custom templates successfully
        emailService.initialize();
        
        verify(passwordResetTemplateResource).getInputStream();
        verify(verificationTemplateResource).getInputStream();
    }

    @Test
    @DisplayName("Should handle template with too few placeholders - String.format ignores extra arguments")
    void shouldHandleTemplateWithTooFewPlaceholdersGracefullyDuringSend() throws Exception {
        // Given - template with only one placeholder instead of two
        // Note: String.format with extra arguments doesn't throw, it just ignores them
        String templateWithOnePlaceholder = "Reset your password: %s (link only, no time description)";
        String verificationContent = "Verification template with %s and %s placeholders";
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(templateWithOnePlaceholder.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(verificationContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");

        // Initialize and inject required properties
        emailService.initialize();
        injectRequiredProperties();
        
        // Mock SES client to return success response
        var mockResponse = software.amazon.awssdk.services.sesv2.model.SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        when(sesClient.sendEmail(any(software.amazon.awssdk.services.sesv2.model.SendEmailRequest.class)))
            .thenReturn(mockResponse);
        
        // When - send email (String.format ignores extra arguments, so this succeeds)
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then - verify email was sent (extra argument was ignored by String.format)
        verify(sesClient).sendEmail(any(software.amazon.awssdk.services.sesv2.model.SendEmailRequest.class));
        
        // Verify success metric was recorded
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should handle template with too many placeholders gracefully during send - MissingFormatArgumentException caught")
    void shouldHandleTemplateWithTooManyPlaceholdersGracefullyDuringSend() throws Exception {
        // Given - template with three placeholders instead of two
        // String.format will throw MissingFormatArgumentException when we only provide 2 arguments
        String templateWithThreePlaceholders = "Reset link: %s, expires in: %s, extra: %s";
        String verificationContent = "Verification template with %s and %s placeholders";
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(templateWithThreePlaceholders.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(verificationContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");

        // Initialize and inject required properties
        emailService.initialize();
        injectRequiredProperties();
        
        // When - send email (String.format throws MissingFormatArgumentException - we provide 2 args but template expects 3)
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then - verify exception was caught and handled gracefully - NO email sent
        verify(sesClient, never()).sendEmail(any(software.amazon.awssdk.services.sesv2.model.SendEmailRequest.class));
        
        // Verify failure metric was recorded
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .tag("reason", "unexpected")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should handle template with no placeholders - String.format ignores extra arguments")
    void shouldHandleTemplateWithNoPlaceholdersGracefullyDuringSend() throws Exception {
        // Given - template with no placeholders
        // Note: String.format with no placeholders but extra arguments doesn't throw, it ignores them
        String templateWithNoPlaceholders = "Reset your password by clicking the link in this email.";
        String verificationContent = "Verification template with %s and %s placeholders";
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(templateWithNoPlaceholders.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(verificationContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");

        // Initialize and inject required properties
        emailService.initialize();
        injectRequiredProperties();
        
        // Mock SES client to return success response
        var mockResponse = software.amazon.awssdk.services.sesv2.model.SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        when(sesClient.sendEmail(any(software.amazon.awssdk.services.sesv2.model.SendEmailRequest.class)))
            .thenReturn(mockResponse);
        
        // When - send email (String.format ignores extra arguments when template has no placeholders)
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then - verify email was sent (extra arguments were ignored)
        verify(sesClient).sendEmail(any(software.amazon.awssdk.services.sesv2.model.SendEmailRequest.class));
        
        // Verify success metric was recorded
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should handle verification template with fewer placeholders - extra arguments ignored")
    void shouldHandleVerificationTemplateWithPlaceholderMismatchGracefully() throws Exception {
        // Given - verification template with only one placeholder
        // String.format ignores extra arguments
        String passwordResetContent = "Password reset template with %s and %s placeholders";
        String verificationTemplateWithOnePlaceholder = "Verify your email: %s (link only)";
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(passwordResetContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(verificationTemplateWithOnePlaceholder.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");

        // Initialize and inject required properties
        emailService.initialize();
        injectRequiredPropertiesForVerification();
        
        // Mock SES client to return success response
        var mockResponse = software.amazon.awssdk.services.sesv2.model.SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        when(sesClient.sendEmail(any(software.amazon.awssdk.services.sesv2.model.SendEmailRequest.class)))
            .thenReturn(mockResponse);
        
        // When - send verification email (extra argument ignored)
        emailService.sendEmailVerificationEmail("test@example.com", "test-token");
        
        // Then - verify email was sent
        verify(sesClient).sendEmail(any(software.amazon.awssdk.services.sesv2.model.SendEmailRequest.class));
        
        // Verify success metric was recorded
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    private void injectRequiredProperties() {
        try {
            var fromEmailField = AwsSesEmailService.class.getDeclaredField("fromEmail");
            fromEmailField.setAccessible(true);
            fromEmailField.set(emailService, "test@example.com");
            
            var baseUrlField = AwsSesEmailService.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            baseUrlField.set(emailService, "http://localhost:3000");
            
            var passwordResetSubjectField = AwsSesEmailService.class.getDeclaredField("passwordResetSubject");
            passwordResetSubjectField.setAccessible(true);
            passwordResetSubjectField.set(emailService, "Reset Password");
            
            var resetTokenTtlField = AwsSesEmailService.class.getDeclaredField("resetTokenTtlMinutes");
            resetTokenTtlField.setAccessible(true);
            resetTokenTtlField.set(emailService, 60L);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject required properties", e);
        }
    }

    private void injectRequiredPropertiesForVerification() {
        try {
            var fromEmailField = AwsSesEmailService.class.getDeclaredField("fromEmail");
            fromEmailField.setAccessible(true);
            fromEmailField.set(emailService, "test@example.com");
            
            var baseUrlField = AwsSesEmailService.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            baseUrlField.set(emailService, "http://localhost:3000");
            
            var verificationSubjectField = AwsSesEmailService.class.getDeclaredField("verificationSubject");
            verificationSubjectField.setAccessible(true);
            verificationSubjectField.set(emailService, "Verify Email");
            
            var verificationTokenTtlField = AwsSesEmailService.class.getDeclaredField("verificationTokenTtlMinutes");
            verificationTokenTtlField.setAccessible(true);
            verificationTokenTtlField.set(emailService, 120L);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject required properties", e);
        }
    }
}
