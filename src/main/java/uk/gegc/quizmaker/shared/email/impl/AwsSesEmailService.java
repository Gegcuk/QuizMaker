package uk.gegc.quizmaker.shared.email.impl;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import uk.gegc.quizmaker.shared.email.EmailService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * AWS SES-based email service implementation using SESv2 API over HTTPS.
 * 
 * This implementation:
 * - Uses AWS SDK v2 SES client to send emails via HTTPS API (not SMTP)
 * - Works in environments where port 25 is blocked
 * - Provides better observability (SES message IDs)
 * - Implements safe error handling (never leaks recipient info)
 * 
 * Configuration:
 * - Requires AWS credentials via default credential chain (env vars, IAM roles, etc.)
 * - Requires verified sending identity in SES
 * - Region must be configured via app.email.region
 */
@Slf4j
public class AwsSesEmailService implements EmailService {

    private static final String EMAIL_TYPE_PASSWORD_RESET = "password-reset";
    private static final String EMAIL_TYPE_VERIFICATION = "email-verification";
    private static final String EMAIL_TYPE_NOTIFICATION = "notification";

    private final SesV2Client sesClient;
    private final MeterRegistry meterRegistry;
    
    @Value("${app.email.from}")
    private String fromEmail;
    
    @Value("${app.frontend.base-url}")
    private String baseUrl;
    
    @Value("${app.email.password-reset.subject}")
    private String passwordResetSubject;
    
    @Value("${app.email.verification.subject}")
    private String verificationSubject;
    
    @Value("${app.auth.reset-token-ttl-minutes}")
    private long resetTokenTtlMinutes;
    
    @Value("${app.auth.verification-token-ttl-minutes}")
    private long verificationTokenTtlMinutes;
    
    @Value("${app.email.ses.configuration-set:#{null}}")
    private String configurationSetName;

    @Value("${app.email.templates.password-reset:classpath:email/password-reset-email.txt}")
    private Resource passwordResetTemplateResource;

    @Value("${app.email.templates.verification:classpath:email/email-verification-email.txt}")
    private Resource verificationTemplateResource;

    private String passwordResetTemplate;
    private String verificationTemplate;

    public AwsSesEmailService(SesV2Client sesClient, MeterRegistry meterRegistry) {
        this.sesClient = sesClient;
        this.meterRegistry = meterRegistry;
        log.info("AWS SES Email Service created (configuration will be injected)");
    }

    @PostConstruct
    void initialize() {
        validateSenderConfiguration();
        loadTemplates();
    }

    private void validateSenderConfiguration() {
        if (fromEmail == null || fromEmail.isBlank() || fromEmail.equals("noreply@example.com")) {
            log.warn("AWS SES Email Service: fromEmail is not configured or using default placeholder. " +
                    "Ensure app.email.from is set to a verified identity in SES.");
        } else {
            log.info("AWS SES Email Service initialized with sender: {} | Region: configured via AWS SDK | " +
                    "Retry mode: Standard (AWS SDK default with exponential backoff)", fromEmail);
        }
    }

    private void loadTemplates() {
        this.passwordResetTemplate = loadTemplate(passwordResetTemplateResource, "password reset");
        this.verificationTemplate = loadTemplate(verificationTemplateResource, "email verification");
    }

    private String loadTemplate(Resource resource, String templateName) {
        if (resource == null) {
            throw new IllegalStateException("Missing resource for " + templateName + " email template");
        }
        try {
            try (var inputStream = resource.getInputStream()) {
                String content = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException(templateName + " template is empty: " + resource.getDescription());
                }
                return content;
            }
        } catch (Exception ex) {
            log.error("Failed to load {} email template from resource: {}", templateName, resource.getDescription(), ex);
            throw new IllegalStateException("Cannot load " + templateName + " email template", ex);
        }
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetToken) {
        try {
            String emailContent = createPasswordResetEmailContent(resetToken);
            
            SendEmailRequest request = buildEmailRequest(
                    email,
                    passwordResetSubject,
                    emailContent
            );
            
            SendEmailResponse response = sesClient.sendEmail(request);
            log.info("Password reset email sent to: {} | SES MessageId: {}", 
                    maskEmail(email), response.messageId());
            recordSuccess(EMAIL_TYPE_PASSWORD_RESET);
            
        } catch (SesV2Exception e) {
            handleSesException("password reset", EMAIL_TYPE_PASSWORD_RESET, email, e);
        } catch (Exception e) {
            // Catch any other unexpected exceptions to prevent information leakage
            log.error("Unexpected error sending password reset email to: {}", maskEmail(email), e);
            recordFailure(EMAIL_TYPE_PASSWORD_RESET, "unexpected");
        }
    }

    @Override
    public void sendEmailVerificationEmail(String email, String verificationToken) {
        try {
            String emailContent = createEmailVerificationContent(verificationToken);
            
            SendEmailRequest request = buildEmailRequest(
                    email,
                    verificationSubject,
                    emailContent
            );
            
            SendEmailResponse response = sesClient.sendEmail(request);
            log.info("Email verification email sent to: {} | SES MessageId: {}", 
                    maskEmail(email), response.messageId());
            recordSuccess(EMAIL_TYPE_VERIFICATION);
            
        } catch (SesV2Exception e) {
            handleSesException("email verification", EMAIL_TYPE_VERIFICATION, email, e);
        } catch (Exception e) {
            // Catch any other unexpected exceptions to prevent information leakage
            log.error("Unexpected error sending email verification to: {}", maskEmail(email), e);
            recordFailure(EMAIL_TYPE_VERIFICATION, "unexpected");
        }
    }

    @Override
    public void sendPlainTextEmail(String to, String subject, String body) {
        try {
            SendEmailRequest request = buildEmailRequest(to, subject, body);
            SendEmailResponse response = sesClient.sendEmail(request);
            log.info("Plain text email sent to: {} | SES MessageId: {}", maskEmail(to), response.messageId());
            recordSuccess(EMAIL_TYPE_NOTIFICATION);
        } catch (SesV2Exception e) {
            handleSesException("plain text notification", EMAIL_TYPE_NOTIFICATION, to, e);
        } catch (Exception e) {
            log.error("Unexpected error sending plain text email to: {}", maskEmail(to), e);
            recordFailure(EMAIL_TYPE_NOTIFICATION, "unexpected");
        }
    }

    /**
     * Builds a standard SES SendEmailRequest with simple text content.
     */
    private SendEmailRequest buildEmailRequest(String toEmail, String subject, String textContent) {
        EmailContent emailContent = EmailContent.builder()
                .simple(Message.builder()
                        .subject(Content.builder()
                                .data(subject)
                                .charset("UTF-8")
                                .build())
                        .body(Body.builder()
                                .text(Content.builder()
                                        .data(textContent)
                                        .charset("UTF-8")
                                        .build())
                                .build())
                        .build())
                .build();

        Destination destination = Destination.builder()
                .toAddresses(toEmail)
                .build();

        SendEmailRequest.Builder requestBuilder = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(destination)
                .content(emailContent);

        // Add configuration set if configured (for event tracking)
        // Note: Configuration set must exist in AWS SES console, otherwise email will fail
        if (configurationSetName != null && !configurationSetName.isBlank()) {
            requestBuilder.configurationSetName(configurationSetName);
        }

        return requestBuilder.build();
    }

    /**
     * Handles SES-specific exceptions with appropriate logging and classification.
     * 
     * Error handling strategy:
     * - Client errors (4xx): log and drop (bad request, suppressed address, etc.)
     * - Server errors (5xx): log with higher severity (SES service issues)
     * - Never expose recipient details in logs to prevent enumeration
     */
    private void handleSesException(String emailType, String metricType, String email, SesV2Exception e) {
        String maskedEmail = maskEmail(email);
        
        // Check for specific error conditions
        if (e.statusCode() >= 400 && e.statusCode() < 500) {
            // Client errors: invalid recipient, suppressed address, configuration issue
            log.warn("Failed to send {} email to: {} | SES Error: {} (status: {})", 
                    emailType, maskedEmail, e.awsErrorDetails().errorMessage(), e.statusCode());
            recordFailure(metricType, "client" + statusSuffix(e.statusCode()));
        } else if (e.statusCode() >= 500) {
            // Server errors: SES service issues, should be retried by infrastructure
            log.error("SES service error sending {} email to: {} | Error: {} (status: {})", 
                    emailType, maskedEmail, e.awsErrorDetails().errorMessage(), e.statusCode());
            recordFailure(metricType, "server" + statusSuffix(e.statusCode()));
        } else {
            // Other errors
            log.error("Failed to send {} email to: {} | SES Error: {}", 
                    emailType, maskedEmail, e.awsErrorDetails().errorMessage(), e);
            recordFailure(metricType, "unknown");
        }
    }

    private String statusSuffix(int statusCode) {
        return statusCode > 0 ? ("-" + statusCode) : "";
    }

    private String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***@" + (atIndex > 0 ? email.substring(atIndex + 1) : "***");
        }
        return email.charAt(0) + "***@" + email.substring(atIndex + 1);
    }

    private String createPasswordResetEmailContent(String resetToken) {
        String encodedToken = URLEncoder.encode(resetToken, StandardCharsets.UTF_8);
        String resetUrl = baseUrl + "/reset-password?token=" + encodedToken;
        
        String timeDescription = formatTimeDescription(resetTokenTtlMinutes);

        return String.format(passwordResetTemplate, resetUrl, timeDescription);
    }

    private String createEmailVerificationContent(String verificationToken) {
        String encodedToken = URLEncoder.encode(verificationToken, StandardCharsets.UTF_8);
        String verificationUrl = baseUrl + "/verify-email?token=" + encodedToken;
        
        String timeDescription = formatTimeDescription(verificationTokenTtlMinutes);

        return String.format(verificationTemplate, verificationUrl, timeDescription);
    }
    
    private String formatTimeDescription(long minutes) {
        if (minutes == 60) {
            return "1 hour";
        } else if (minutes < 60) {
            return minutes + " minutes";
        } else {
            long hours = minutes / 60;
            long remainingMinutes = minutes % 60;
            if (remainingMinutes == 0) {
                return hours + " hour" + (hours > 1 ? "s" : "");
            } else {
                return hours + " hour" + (hours > 1 ? "s" : "") + " and " + remainingMinutes + " minutes";
            }
        }
    }

    private void recordSuccess(String type) {
        if (meterRegistry != null) {
            meterRegistry.counter("email.sent", "provider", "ses", "type", type).increment();
        }
    }

    private void recordFailure(String type, String reason) {
        if (meterRegistry != null) {
            String sanitizedReason = reason == null ? "unknown" : reason.toLowerCase(Locale.ENGLISH);
            meterRegistry.counter("email.failed", "provider", "ses", "type", type, "reason", sanitizedReason).increment();
        }
    }
}
