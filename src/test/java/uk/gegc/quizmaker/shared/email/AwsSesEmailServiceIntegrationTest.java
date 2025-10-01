package uk.gegc.quizmaker.shared.email;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import uk.gegc.quizmaker.shared.email.impl.AwsSesEmailService;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for AwsSesEmailService with mocked SES client.
 * 
 * These tests verify the complete email service behavior with Spring context
 * while using a mocked SES client to avoid actual AWS calls.
 * 
 * Tests cover:
 * - Happy path: successful email sending with metrics and logging
 * - Error paths: client errors (400, 403), throttling (429), server errors (500)
 * - Request validation: proper SES request construction
 * - Metrics recording: success and failure counters
 * - Logging behavior: appropriate log levels and email masking
 * 
 * Each test uses a real Spring context with SES provider configured,
 * but replaces the SesV2Client bean with a Mockito mock.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "app.email.provider=ses",
    "app.email.from=test-sender@verified-domain.com",
    "app.email.region=us-east-1",
    "AWS_ACCESS_KEY_ID=test-access-key",
    "AWS_SECRET_ACCESS_KEY=test-secret-key",
    "app.email.password-reset.subject=Reset Your Password",
    "app.email.verification.subject=Verify Your Email",
    "app.frontend.base-url=http://localhost:3000",
    "app.auth.reset-token-ttl-minutes=60",
    "app.auth.verification-token-ttl-minutes=1440",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@DisplayName("AWS SES Email Service Integration Tests")
class AwsSesEmailServiceIntegrationTest {

    @TestConfiguration
    static class MockSesClientConfiguration {
        @Bean
        @Primary
        public SesV2Client mockedSesV2Client() {
            return mock(SesV2Client.class);
        }

        @Bean
        @Primary
        public MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private EmailService emailService;

    @MockitoBean
    private SesV2Client sesV2Client;

    @Autowired
    private MeterRegistry meterRegistry;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        // Set up log capture
        logger = (Logger) LoggerFactory.getLogger(AwsSesEmailService.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        logger.setLevel(Level.TRACE);

        // Reset meter registry
        if (meterRegistry instanceof SimpleMeterRegistry) {
            ((SimpleMeterRegistry) meterRegistry).clear();
        }
        
        // Reset mock between tests (Mockito does this automatically, but being explicit)
        reset(sesV2Client);
    }

    @AfterEach
    void tearDown() {
        if (listAppender != null) {
            listAppender.stop();
            logger.detachAppender(listAppender);
        }
    }

    // ========== Happy Path Tests ==========

    @Test
    @DisplayName("sendPasswordResetEmail: when SES succeeds then email sent and metrics incremented")
    void sendPasswordResetEmail_whenSesSucceeds_thenEmailSentAndMetricsIncremented() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-reset-token-123";
        String fakeMessageId = "mock-message-id-abc123";

        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId(fakeMessageId)
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify SES was called
        verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest.class));

        // Then - verify metrics
        double successCount = meterRegistry.counter("email.sent",
            "provider", "ses",
            "type", "password-reset").count();
        assertThat(successCount).isEqualTo(1.0);

        // Then - verify logging (email should be masked)
        List<String> infoMessages = getLogMessages(Level.INFO);
        assertThat(infoMessages).anyMatch(msg -> 
            msg.contains("Password reset email sent to:") && 
            msg.contains("u***@example.com") && 
            msg.contains(fakeMessageId));
    }

    @Test
    @DisplayName("sendEmailVerificationEmail: when SES succeeds then email sent and metrics incremented")
    void sendEmailVerificationEmail_whenSesSucceeds_thenEmailSentAndMetricsIncremented() {
        // Given
        String testEmail = "newuser@example.com";
        String testToken = "test-verification-token-456";
        String fakeMessageId = "mock-message-id-xyz789";

        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId(fakeMessageId)
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        emailService.sendEmailVerificationEmail(testEmail, testToken);

        // Then - verify SES was called
        verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest.class));

        // Then - verify metrics
        double successCount = meterRegistry.counter("email.sent",
            "provider", "ses",
            "type", "email-verification").count();
        assertThat(successCount).isEqualTo(1.0);

        // Then - verify logging
        List<String> infoMessages = getLogMessages(Level.INFO);
        assertThat(infoMessages).anyMatch(msg -> 
            msg.contains("Email verification email sent to:") && 
            msg.contains("n***@example.com") &&
            msg.contains(fakeMessageId));
    }

    @Test
    @DisplayName("sendEmail: when successful then request fields match expectations")
    void sendEmail_whenSuccessful_thenRequestFieldsMatchExpectations() {
        // Given
        String testEmail = "recipient@example.com";
        String testToken = "test-token";

        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-id")
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - capture and verify the request
        verify(sesV2Client).sendEmail(argThat((SendEmailRequest request) -> {
            assertThat(request.fromEmailAddress()).isEqualTo("test-sender@verified-domain.com");
            assertThat(request.destination().toAddresses()).containsExactly(testEmail);
            
            EmailContent content = request.content();
            assertThat(content.simple().subject().data()).isEqualTo("Reset Your Password");
            assertThat(content.simple().body().text().data())
                .contains("http://localhost:3000")
                .contains(testToken)
                .contains("1 hour"); // 60 minutes = 1 hour
            assertThat(content.simple().body().text().charset()).isEqualTo("UTF-8");
            
            return true;
        }));
    }

    // ========== Client Error Tests ==========

    @Test
    @DisplayName("sendPasswordResetEmail: when SES returns 400 then WARN logged and failure metric incremented")
    void sendPasswordResetEmail_whenSesReturns400_thenWarnLoggedAndFailureMetricIncremented() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        SesV2Exception clientException = (SesV2Exception) SesV2Exception.builder()
            .message("Bad Request")
            .statusCode(400)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Bad Request")
                .errorCode("BadRequestException")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(clientException);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify failure metrics
        double failureCount = meterRegistry.counter("email.failed",
            "provider", "ses",
            "type", "password-reset",
            "reason", "client-400").count();
        assertThat(failureCount).isEqualTo(1.0);

        // Then - verify WARN logging
        List<String> warnMessages = getLogMessages(Level.WARN);
        assertThat(warnMessages).anyMatch(msg -> 
            msg.contains("Failed to send password reset email to:") &&
            msg.contains("400") &&
            msg.contains("u***@example.com"));
    }

    @Test
    @DisplayName("sendPasswordResetEmail: when SES returns 403 then WARN logged and client-403 metric incremented")
    void sendPasswordResetEmail_whenSesReturns403_thenWarnLoggedAndClient403MetricIncremented() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        SesV2Exception clientException = (SesV2Exception) SesV2Exception.builder()
            .message("Forbidden")
            .statusCode(403)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Forbidden")
                .errorCode("AccessDeniedException")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(clientException);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify failure metrics
        double failureCount = meterRegistry.counter("email.failed",
            "provider", "ses",
            "type", "password-reset",
            "reason", "client-403").count();
        assertThat(failureCount).isEqualTo(1.0);

        // Then - verify WARN logging
        List<String> warnMessages = getLogMessages(Level.WARN);
        assertThat(warnMessages).anyMatch(msg -> 
            msg.contains("Failed to send password reset email to:") &&
            msg.contains("403"));
    }

    // ========== Throttling Tests ==========

    @Test
    @DisplayName("sendPasswordResetEmail: when SES returns 429 then WARN logged and client-429 metric incremented")
    void sendPasswordResetEmail_whenSesReturns429_thenWarnLoggedAndClient429MetricIncremented() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        SesV2Exception throttlingException = (SesV2Exception) SesV2Exception.builder()
            .message("Rate exceeded")
            .statusCode(429)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Rate exceeded")
                .errorCode("TooManyRequestsException")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(throttlingException);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify failure metrics
        double failureCount = meterRegistry.counter("email.failed",
            "provider", "ses",
            "type", "password-reset",
            "reason", "client-429").count();
        assertThat(failureCount).isEqualTo(1.0);

        // Then - verify WARN logging (throttling is treated as client error)
        List<String> warnMessages = getLogMessages(Level.WARN);
        assertThat(warnMessages).anyMatch(msg -> 
            msg.contains("Failed to send password reset email to:") &&
            msg.contains("429"));
    }

    // ========== Server Error Tests ==========

    @Test
    @DisplayName("sendPasswordResetEmail: when SES returns 500 then ERROR logged and server-500 metric incremented")
    void sendPasswordResetEmail_whenSesReturns500_thenErrorLoggedAndServer500MetricIncremented() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        SesV2Exception serverException = (SesV2Exception) SesV2Exception.builder()
            .message("Internal Server Error")
            .statusCode(500)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Internal Server Error")
                .errorCode("InternalServerError")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(serverException);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify failure metrics
        double failureCount = meterRegistry.counter("email.failed",
            "provider", "ses",
            "type", "password-reset",
            "reason", "server-500").count();
        assertThat(failureCount).isEqualTo(1.0);

        // Then - verify ERROR logging
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages).anyMatch(msg -> 
            msg.contains("SES service error sending password reset email to:") &&
            msg.contains("500") &&
            msg.contains("u***@example.com"));
    }

    @Test
    @DisplayName("sendPasswordResetEmail: when SES returns 503 then ERROR logged and server-503 metric incremented")
    void sendPasswordResetEmail_whenSesReturns503_thenErrorLoggedAndServer503MetricIncremented() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        SesV2Exception serverException = (SesV2Exception) SesV2Exception.builder()
            .message("Service Unavailable")
            .statusCode(503)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Service Unavailable")
                .errorCode("ServiceUnavailableException")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(serverException);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify failure metrics
        double failureCount = meterRegistry.counter("email.failed",
            "provider", "ses",
            "type", "password-reset",
            "reason", "server-503").count();
        assertThat(failureCount).isEqualTo(1.0);

        // Then - verify ERROR logging
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages).anyMatch(msg -> 
            msg.contains("SES service error sending password reset email to:") &&
            msg.contains("503"));
    }

    // ========== Unexpected Error Tests ==========

    @Test
    @DisplayName("sendPasswordResetEmail: when unexpected RuntimeException then ERROR logged and unexpected metric incremented")
    void sendPasswordResetEmail_whenUnexpectedRuntimeException_thenErrorLoggedAndUnexpectedMetricIncremented() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(new RuntimeException("Unexpected error"));

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify failure metrics
        double failureCount = meterRegistry.counter("email.failed",
            "provider", "ses",
            "type", "password-reset",
            "reason", "unexpected").count();
        assertThat(failureCount).isEqualTo(1.0);

        // Then - verify ERROR logging
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages).anyMatch(msg -> 
            msg.contains("Unexpected error") &&
            msg.contains("u***@example.com"));
    }

    // ========== Email Masking Tests ==========

    @Test
    @DisplayName("Email addresses should be masked in all log paths")
    void emailAddressesShouldBeMaskedInAllLogPaths() {
        // Given
        String testEmail = "testuser@example.com";
        String testToken = "test-token";

        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-id")
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify full email is never logged
        List<String> allMessages = listAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());

        assertThat(allMessages)
            .noneMatch(msg -> msg.contains("testuser@example.com"))
            .anyMatch(msg -> msg.contains("t***@example.com"));
    }

    @Test
    @DisplayName("Email addresses should be masked in error paths")
    void emailAddressesShouldBeMaskedInErrorPaths() {
        // Given
        String testEmail = "sensitive@example.com";
        String testToken = "test-token";

        SesV2Exception clientException = (SesV2Exception) SesV2Exception.builder()
            .message("Bad Request")
            .statusCode(400)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Bad Request")
                .errorCode("BadRequestException")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(clientException);

        // When
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify full email is never logged
        List<String> allMessages = listAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());

        assertThat(allMessages)
            .noneMatch(msg -> msg.contains("sensitive@example.com"))
            .anyMatch(msg -> msg.contains("s***@example.com"));
    }

    // ========== Multiple Errors Tests ==========

    @Test
    @DisplayName("Multiple errors should increment counters correctly for different types")
    void multipleErrorsShouldIncrementCountersCorrectlyForDifferentTypes() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        SesV2Exception exception400 = (SesV2Exception) SesV2Exception.builder()
            .statusCode(400)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Bad Request")
                .errorCode("BadRequestException")
                .build())
            .build();
        SesV2Exception exception500 = (SesV2Exception) SesV2Exception.builder()
            .statusCode(500)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Internal Server Error")
                .errorCode("InternalServerError")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(exception400)
            .thenThrow(exception500)
            .thenThrow(new RuntimeException("Unexpected"));

        // When - send three emails with different error types
        emailService.sendPasswordResetEmail(testEmail, testToken);
        emailService.sendEmailVerificationEmail(testEmail, testToken);
        emailService.sendPasswordResetEmail(testEmail, testToken);

        // Then - verify each error type incremented its own counter
        double client400Count = meterRegistry.counter("email.failed",
            "provider", "ses", "type", "password-reset", "reason", "client-400").count();
        double server500Count = meterRegistry.counter("email.failed",
            "provider", "ses", "type", "email-verification", "reason", "server-500").count();
        double unexpectedCount = meterRegistry.counter("email.failed",
            "provider", "ses", "type", "password-reset", "reason", "unexpected").count();

        assertThat(client400Count).isEqualTo(1.0);
        assertThat(server500Count).isEqualTo(1.0);
        assertThat(unexpectedCount).isEqualTo(1.0);
    }

    // ========== Service Never Throws Tests ==========

    @Test
    @DisplayName("Email service should never throw exceptions to caller on SES errors")
    void emailServiceShouldNeverThrowExceptionsToCallerOnSesErrors() {
        // Given
        String testEmail = "user@example.com";
        String testToken = "test-token";

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(new RuntimeException("Critical SES failure"));

        // When / Then - should not throw
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
            emailService.sendPasswordResetEmail(testEmail, testToken));
        
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
            emailService.sendEmailVerificationEmail(testEmail, testToken));
    }

    // ========== Helper Methods ==========

    private List<String> getLogMessages(Level level) {
        return listAppender.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }
}

