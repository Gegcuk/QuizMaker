package uk.gegc.quizmaker.shared.email.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for missing functionality in AwsSesEmailService.
 * 
 * Tests cover:
 * - Template placeholder mismatch handling
 * - Request builder charsets
 * - Verification subject mapping
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Missing Tests")
class AwsSesEmailServiceMissingTests {

    @Mock
    private SesV2Client sesClient;
    @Mock
    private Resource passwordResetTemplateResource;
    @Mock
    private Resource verificationTemplateResource;
    
    private SimpleMeterRegistry meterRegistry;
    private AwsSesEmailService emailService;

    private static final String NORMAL_PASSWORD_RESET_TEMPLATE = "Reset your password: %s | Valid for: %s";
    private static final String NORMAL_VERIFICATION_TEMPLATE = "Verify your email: %s | Valid for: %s";

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        // Set up normal templates by default
        setupNormalTemplates();
        injectRequiredProperties();
        
        // Initialize the service to load templates
        emailService.initialize();
        
        // Mock successful SES response
        var mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();
        lenient().when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(mockResponse);
    }

    // ========== Template Placeholder Mismatch Handling ==========

    @Test
    @DisplayName("Password reset template with too many placeholders (3) should catch formatting error and not send email")
    void passwordResetTemplateWithTooManyPlaceholdersShouldCatchErrorAndNotSendEmail() throws Exception {
        // Given - template with 3 placeholders but we only provide 2 arguments
        String templateWithThreePlaceholders = "Reset link: %s, expires in: %s, extra: %s";
        setupTemplate(passwordResetTemplateResource, templateWithThreePlaceholders);
        emailService.initialize(); // Re-initialize to load the new template
        
        // When - send email (String.format throws MissingFormatArgumentException)
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then - verify email was NOT sent
        verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
        
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
    @DisplayName("Email verification template with too many placeholders (3) should catch formatting error and not send email")
    void emailVerificationTemplateWithTooManyPlaceholdersShouldCatchErrorAndNotSendEmail() throws Exception {
        // Given - template with 3 placeholders but we only provide 2 arguments
        String templateWithThreePlaceholders = "Verify link: %s, expires in: %s, extra: %s";
        setupTemplate(verificationTemplateResource, templateWithThreePlaceholders);
        emailService.initialize(); // Re-initialize to load the new template
        
        // When - send email (String.format throws MissingFormatArgumentException)
        emailService.sendEmailVerificationEmail("test@example.com", "test-token");
        
        // Then - verify email was NOT sent
        verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
        
        // Verify failure metric was recorded
        var counter = meterRegistry.find("email.failed")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .tag("reason", "unexpected")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Password reset template with too few placeholders (1) should send email successfully")
    void passwordResetTemplateWithTooFewPlaceholdersShouldSendEmailSuccessfully() throws Exception {
        // Given - template with only 1 placeholder but we provide 2 arguments
        String templateWithOnePlaceholder = "Reset your password: %s (link only)";
        setupTemplate(passwordResetTemplateResource, templateWithOnePlaceholder);
        emailService.initialize(); // Re-initialize to load the new template
        
        // When - send email (String.format ignores extra arguments)
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then - verify email WAS sent
        verify(sesClient).sendEmail(any(SendEmailRequest.class));
        
        // Verify success metric was recorded
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Email verification template with too few placeholders (1) should send email successfully")
    void emailVerificationTemplateWithTooFewPlaceholdersShouldSendEmailSuccessfully() throws Exception {
        // Given - template with only 1 placeholder but we provide 2 arguments
        String templateWithOnePlaceholder = "Verify your email: %s (link only)";
        setupTemplate(verificationTemplateResource, templateWithOnePlaceholder);
        emailService.initialize(); // Re-initialize to load the new template
        
        // When - send email (String.format ignores extra arguments)
        emailService.sendEmailVerificationEmail("test@example.com", "test-token");
        
        // Then - verify email WAS sent
        verify(sesClient).sendEmail(any(SendEmailRequest.class));
        
        // Verify success metric was recorded
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Password reset template with no placeholders should send email successfully")
    void passwordResetTemplateWithNoPlaceholdersShouldSendEmailSuccessfully() throws Exception {
        // Given - template with no placeholders but we provide 2 arguments
        String templateWithNoPlaceholders = "Reset your password by clicking the link in this email.";
        setupTemplate(passwordResetTemplateResource, templateWithNoPlaceholders);
        emailService.initialize(); // Re-initialize to load the new template
        
        // When - send email (String.format ignores extra arguments)
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then - verify email WAS sent
        verify(sesClient).sendEmail(any(SendEmailRequest.class));
        
        // Verify success metric was recorded
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "password-reset")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Email verification template with no placeholders should send email successfully")
    void emailVerificationTemplateWithNoPlaceholdersShouldSendEmailSuccessfully() throws Exception {
        // Given - template with no placeholders but we provide 2 arguments
        String templateWithNoPlaceholders = "Verify your email by clicking the link in this email.";
        setupTemplate(verificationTemplateResource, templateWithNoPlaceholders);
        emailService.initialize(); // Re-initialize to load the new template
        
        // When - send email (String.format ignores extra arguments)
        emailService.sendEmailVerificationEmail("test@example.com", "test-token");
        
        // Then - verify email WAS sent
        verify(sesClient).sendEmail(any(SendEmailRequest.class));
        
        // Verify success metric was recorded
        var counter = meterRegistry.find("email.sent")
            .tag("provider", "ses")
            .tag("type", "email-verification")
            .counter();
        
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ========== Request Builder Charsets ==========

    @Test
    @DisplayName("Password reset email should have UTF-8 charset for subject and body")
    void passwordResetEmailShouldHaveUtf8CharsetForSubjectAndBody() throws Exception {
        // Given
        setupNormalTemplates();
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        SendEmailRequest capturedRequest = requestCaptor.getValue();
        
        // Verify subject charset
        assertThat(capturedRequest.content().simple().subject().charset()).isEqualTo("UTF-8");
        
        // Verify body charset
        assertThat(capturedRequest.content().simple().body().text().charset()).isEqualTo("UTF-8");
    }

    @Test
    @DisplayName("Email verification should have UTF-8 charset for subject and body")
    void emailVerificationShouldHaveUtf8CharsetForSubjectAndBody() throws Exception {
        // Given
        setupNormalTemplates();
        
        // When
        emailService.sendEmailVerificationEmail("test@example.com", "test-token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        SendEmailRequest capturedRequest = requestCaptor.getValue();
        
        // Verify subject charset
        assertThat(capturedRequest.content().simple().subject().charset()).isEqualTo("UTF-8");
        
        // Verify body charset
        assertThat(capturedRequest.content().simple().body().text().charset()).isEqualTo("UTF-8");
    }

    // ========== Verification Subject Mapping ==========

    @Test
    @DisplayName("Email verification should use configured verificationSubject")
    void emailVerificationShouldUseConfiguredVerificationSubject() throws Exception {
        // Given
        setupNormalTemplates();
        String customVerificationSubject = "Please Verify Your Email Address";
        
        // Inject custom verification subject
        var field = AwsSesEmailService.class.getDeclaredField("verificationSubject");
        field.setAccessible(true);
        field.set(emailService, customVerificationSubject);
        
        // When
        emailService.sendEmailVerificationEmail("test@example.com", "test-token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        SendEmailRequest capturedRequest = requestCaptor.getValue();
        
        // Verify the subject matches the configured verification subject
        assertThat(capturedRequest.content().simple().subject().data()).isEqualTo(customVerificationSubject);
    }

    @Test
    @DisplayName("Password reset should use configured passwordResetSubject")
    void passwordResetShouldUseConfiguredPasswordResetSubject() throws Exception {
        // Given
        setupNormalTemplates();
        String customPasswordResetSubject = "Reset Your Password - Action Required";
        
        // Inject custom password reset subject
        var field = AwsSesEmailService.class.getDeclaredField("passwordResetSubject");
        field.setAccessible(true);
        field.set(emailService, customPasswordResetSubject);
        
        // When
        emailService.sendPasswordResetEmail("test@example.com", "test-token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(requestCaptor.capture());
        
        SendEmailRequest capturedRequest = requestCaptor.getValue();
        
        // Verify the subject matches the configured password reset subject
        assertThat(capturedRequest.content().simple().subject().data()).isEqualTo(customPasswordResetSubject);
    }

    // ========== Helper Methods ==========

    private void setupNormalTemplates() throws Exception {
        setupTemplate(passwordResetTemplateResource, NORMAL_PASSWORD_RESET_TEMPLATE);
        setupTemplate(verificationTemplateResource, NORMAL_VERIFICATION_TEMPLATE);
    }

    private void setupTemplate(Resource resource, String templateContent) throws Exception {
        lenient().when(resource.getInputStream())
            .thenReturn(new ByteArrayInputStream(templateContent.getBytes(StandardCharsets.UTF_8)));
        lenient().when(resource.getDescription()).thenReturn("test-template");
        
        // Inject the resource into the email service
        var field = AwsSesEmailService.class.getDeclaredField(
            resource == passwordResetTemplateResource ? "passwordResetTemplateResource" : "verificationTemplateResource");
        field.setAccessible(true);
        field.set(emailService, resource);
        
        // Also ensure the other template is set up with default content
        if (resource == passwordResetTemplateResource) {
            // Set up verification template with default content
            lenient().when(verificationTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream(NORMAL_VERIFICATION_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
            lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");
            
            var verificationField = AwsSesEmailService.class.getDeclaredField("verificationTemplateResource");
            verificationField.setAccessible(true);
            verificationField.set(emailService, verificationTemplateResource);
        } else {
            // Set up password reset template with default content
            lenient().when(passwordResetTemplateResource.getInputStream())
                .thenReturn(new ByteArrayInputStream(NORMAL_PASSWORD_RESET_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
            lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
            
            var passwordResetField = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
            passwordResetField.setAccessible(true);
            passwordResetField.set(emailService, passwordResetTemplateResource);
        }
    }

    private void injectRequiredProperties() throws Exception {
        var fromEmailField = AwsSesEmailService.class.getDeclaredField("fromEmail");
        fromEmailField.setAccessible(true);
        fromEmailField.set(emailService, "test@example.com");
        
        var baseUrlField = AwsSesEmailService.class.getDeclaredField("baseUrl");
        baseUrlField.setAccessible(true);
        baseUrlField.set(emailService, "http://localhost:3000");
        
        var passwordResetSubjectField = AwsSesEmailService.class.getDeclaredField("passwordResetSubject");
        passwordResetSubjectField.setAccessible(true);
        passwordResetSubjectField.set(emailService, "Reset Password");
        
        var verificationSubjectField = AwsSesEmailService.class.getDeclaredField("verificationSubject");
        verificationSubjectField.setAccessible(true);
        verificationSubjectField.set(emailService, "Verify Email");
        
        var resetTokenTtlField = AwsSesEmailService.class.getDeclaredField("resetTokenTtlMinutes");
        resetTokenTtlField.setAccessible(true);
        resetTokenTtlField.set(emailService, 60L);
        
        var verificationTokenTtlField = AwsSesEmailService.class.getDeclaredField("verificationTokenTtlMinutes");
        verificationTokenTtlField.setAccessible(true);
        verificationTokenTtlField.set(emailService, 120L);
    }
}
