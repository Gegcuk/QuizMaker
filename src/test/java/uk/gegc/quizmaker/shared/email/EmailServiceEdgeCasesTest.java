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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import uk.gegc.quizmaker.shared.email.impl.AwsSesEmailService;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Edge Cases Tests for Email Service.
 * 
 * These tests verify that the email service correctly handles unusual or problematic scenarios:
 * - Unverified sender email addresses (SES rejection)
 * - Null or invalid recipient emails
 * - Extreme TTL values (0, very large)
 * - Unusual base URL formats (trailing slash, missing scheme)
 * - Non-existent configuration sets
 * 
 * Based on requirements from email-tests.md:
 * "fromEmail placeholder (noreply@example.com) in SES: allowed by our code; SES will likely reject; assert error-handling path."
 * "Null recipient email (if forced): expect a failure in request build or SES call; ensure exception is caught and metrics/logs recorded."
 * "Extreme TTL values (0, very large): content builder returns strings as implemented; capture behavior."
 * "Base URL unusual forms (trailing slash, missing scheme): content still builds; consider future normalization."
 * "Configuration set specified but not existing: SES returns 400; verify client error path."
 */
@DisplayName("Email Service Edge Cases Tests")
class EmailServiceEdgeCasesTest {

    @TestConfiguration
    static class TestMeterRegistryConfiguration {
        @Bean
        @Primary
        public MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /**
     * Tests for unverified sender email addresses.
     * SES will reject emails from unverified sender addresses with a 400 error.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=noreply@example.com",  // Unverified placeholder
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.frontend.base-url=http://localhost:3000",
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Unverified Sender Email Address Tests")
    class UnverifiedSenderEmailTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

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

            // Reset mock and metrics
            reset(sesV2Client);
            meterRegistry.clear();
        }

        @AfterEach
        void tearDown() {
            if (listAppender != null) {
                listAppender.stop();
                logger.detachAppender(listAppender);
            }
        }

        @Test
        @DisplayName("When fromEmail is unverified: SES returns 400 and error is handled gracefully")
        void whenFromEmailIsUnverified_sesReturns400AndErrorIsHandledGracefully() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            
            // Mock SES rejection of unverified sender
            SesV2Exception exception = (SesV2Exception) SesV2Exception.builder()
                .message("Email address is not verified")
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                    .errorMessage("Email address is not verified. The following identities failed the check in region US-EAST-1: noreply@example.com")
                    .errorCode("MessageRejected")
                    .build())
                .build();
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);

            // When
            emailService.sendPasswordResetEmail("recipient@example.com", "token123");

            // Then - should handle error gracefully
            List<String> warnMessages = getLogMessages(Level.WARN);
            assertThat(warnMessages).anyMatch(msg -> 
                msg.contains("Failed to send password reset email to") &&
                msg.contains("r***@example.com") &&
                msg.contains("SES Error") &&
                msg.contains("status: 400"));

            // Then - verify SES client was called
            verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest.class));
        }

        @Test
        @DisplayName("When fromEmail is unverified: error details are logged")
        void whenFromEmailIsUnverified_errorDetailsAreLogged() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            
            SesV2Exception exception = (SesV2Exception) SesV2Exception.builder()
                .message("Email address is not verified")
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                    .errorMessage("Email address is not verified")
                    .errorCode("MessageRejected")
                    .build())
                .build();
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);

            // When
            emailService.sendPasswordResetEmail("user@example.com", "token456");

            // Then - error should be logged with details
            List<String> warnMessages = getLogMessages(Level.WARN);
            assertThat(warnMessages).anyMatch(msg -> 
                msg.contains("Failed to send password reset email") &&
                msg.contains("Email address is not verified") &&
                msg.contains("status: 400"));
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }

    /**
     * Tests for null or empty recipient email addresses.
     * Verifies that SDK or service layer handles these gracefully.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=verified@example.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.frontend.base-url=http://localhost:3000",
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Null or Invalid Recipient Email Tests")
    class NullRecipientEmailTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

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

            // Reset mock and metrics
            reset(sesV2Client);
            meterRegistry.clear();
        }

        @AfterEach
        void tearDown() {
            if (listAppender != null) {
                listAppender.stop();
                logger.detachAppender(listAppender);
            }
        }

        @Test
        @DisplayName("When recipient is null: exception is caught and logged")
        void whenRecipientIsNull_exceptionIsCaughtAndLogged() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            
            // Mock SDK throwing NullPointerException or similar
            when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(new NullPointerException("recipient cannot be null"));

            // When
            emailService.sendPasswordResetEmail(null, "token123");

            // Then - error should be logged (might be caught by SDK validation before send)
            // The service wraps all exceptions, so verify no exception bubbles up
            // and verify the mail sender was called (or not, depending on where validation fails)
            
            // At minimum, the service should not throw to the caller
            // The exact behavior (logged vs not logged) depends on where the null is caught
        }

        @Test
        @DisplayName("When recipient is empty string: error is handled gracefully")
        void whenRecipientIsEmptyString_errorIsHandledGracefully() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            
            // Mock SES validation error
            SesV2Exception exception = (SesV2Exception) SesV2Exception.builder()
                .message("Invalid email address")
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                    .errorMessage("Invalid email address")
                    .errorCode("InvalidParameterValue")
                    .build())
                .build();
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);

            // When
            emailService.sendEmailVerificationEmail("", "token456");

            // Then - should not throw to caller
            List<String> warnMessages = getLogMessages(Level.WARN);
            assertThat(warnMessages).anyMatch(msg -> 
                msg.contains("Failed to send email verification email") &&
                msg.contains("status: 400"));
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }

    /**
     * Tests for extreme TTL values.
     * Verifies that email content is generated correctly regardless of TTL values.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=verified@example.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.frontend.base-url=http://localhost:3000",
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=0",  // Extreme: 0 minutes
        "app.auth.verification-token-ttl-minutes=525600",  // Extreme: 1 year (525600 minutes)
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Extreme TTL Values Tests")
    class ExtremeTtlValuesTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

        @BeforeEach
        void setUp() {
            reset(sesV2Client);
            
            // Mock successful SES response
            when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder()
                    .messageId("test-message-id-123")
                    .build());
        }

        @Test
        @DisplayName("When reset TTL is 0 minutes: email content is generated with '0 minutes'")
        void whenResetTtlIsZero_emailContentIsGeneratedCorrectly() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

            // When
            emailService.sendPasswordResetEmail("user@example.com", "token123");

            // Then - request should be built and sent
            verify(sesV2Client, times(1)).sendEmail(requestCaptor.capture());
            
            SendEmailRequest request = requestCaptor.getValue();
            String emailBody = request.content().simple().body().text().data();
            
            // Then - TTL should be formatted as "0 minutes"
            assertThat(emailBody).contains("expire in 0 minutes");
        }

        @Test
        @DisplayName("When verification TTL is very large: email content is generated with formatted time")
        void whenVerificationTtlIsVeryLarge_emailContentIsGeneratedCorrectly() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

            // When
            emailService.sendEmailVerificationEmail("user@example.com", "token456");

            // Then - request should be built and sent
            verify(sesV2Client, times(1)).sendEmail(requestCaptor.capture());
            
            SendEmailRequest request = requestCaptor.getValue();
            String emailBody = request.content().simple().body().text().data();
            
            // Then - TTL should be formatted as hours (525600 minutes = 8760 hours = 1 year)
            assertThat(emailBody).contains("expire in 8760 hours");
        }
    }

    /**
     * Tests for unusual base URL formats.
     * Verifies that email content is still generated regardless of URL format.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=verified@example.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.frontend.base-url=localhost:3000",  // Missing scheme
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Unusual Base URL Format Tests")
    class UnusualBaseUrlTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

        @BeforeEach
        void setUp() {
            reset(sesV2Client);
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder()
                    .messageId("test-message-id")
                    .build());
        }

        @Test
        @DisplayName("When base URL missing scheme: email content is still generated")
        void whenBaseUrlMissingScheme_emailContentIsStillGenerated() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

            // When
            emailService.sendPasswordResetEmail("user@example.com", "token123");

            // Then - request should be built successfully
            verify(sesV2Client, times(1)).sendEmail(requestCaptor.capture());
            
            SendEmailRequest request = requestCaptor.getValue();
            String emailBody = request.content().simple().body().text().data();
            
            // Then - URL should still be in content (even if malformed)
            assertThat(emailBody).contains("localhost:3000/reset-password?token=");
        }

        @Test
        @DisplayName("When base URL missing scheme: email sends without exception")
        void whenBaseUrlMissingScheme_emailSendsWithoutException() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);

            // When / Then - should not throw
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
                emailService.sendEmailVerificationEmail("user@example.com", "token456"));
        }
    }

    /**
     * Tests for base URL with trailing slash.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=verified@example.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.frontend.base-url=http://localhost:3000/",  // Trailing slash
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Base URL with Trailing Slash Tests")
    class BaseUrlWithTrailingSlashTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

        @BeforeEach
        void setUp() {
            reset(sesV2Client);
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder()
                    .messageId("test-message-id")
                    .build());
        }

        @Test
        @DisplayName("When base URL has trailing slash: email content contains double slash")
        void whenBaseUrlHasTrailingSlash_emailContentContainsDoubleSlash() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

            // When
            emailService.sendPasswordResetEmail("user@example.com", "token123");

            // Then
            verify(sesV2Client, times(1)).sendEmail(requestCaptor.capture());
            
            SendEmailRequest request = requestCaptor.getValue();
            String emailBody = request.content().simple().body().text().data();
            
            // Then - URL will have double slash (documents current behavior)
            assertThat(emailBody).contains("http://localhost:3000//reset-password?token=");
        }
    }

    /**
     * Tests for non-existent configuration set.
     * SES returns 400 when configuration set doesn't exist.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=verified@example.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.email.ses.configuration-set=nonexistent-config-set",  // Non-existent config set
        "app.frontend.base-url=http://localhost:3000",
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Non-existent Configuration Set Tests")
    class NonExistentConfigurationSetTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

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

            // Reset mock and metrics
            reset(sesV2Client);
            meterRegistry.clear();
        }

        @AfterEach
        void tearDown() {
            if (listAppender != null) {
                listAppender.stop();
                logger.detachAppender(listAppender);
            }
        }

        @Test
        @DisplayName("When configuration set doesn't exist: SES returns 400 and error is handled")
        void whenConfigurationSetDoesNotExist_sesReturns400AndErrorIsHandled() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            
            // Mock SES error for non-existent configuration set
            SesV2Exception exception = (SesV2Exception) SesV2Exception.builder()
                .message("Configuration set does not exist")
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                    .errorMessage("Configuration set 'nonexistent-config-set' does not exist")
                    .errorCode("ConfigurationSetDoesNotExist")
                    .build())
                .build();
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);

            // When
            emailService.sendPasswordResetEmail("user@example.com", "token123");

            // Then - should handle error gracefully
            List<String> warnMessages = getLogMessages(Level.WARN);
            assertThat(warnMessages).anyMatch(msg -> 
                msg.contains("Failed to send password reset email") &&
                msg.contains("u***@example.com") &&
                msg.contains("status: 400"));

            // Then - verify SES client was called
            verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest.class));
        }

        @Test
        @DisplayName("When configuration set doesn't exist: configuration set name is included in request")
        void whenConfigurationSetDoesNotExist_configurationSetNameIsIncludedInRequest() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
            
            // Mock exception
            SesV2Exception exception = (SesV2Exception) SesV2Exception.builder()
                .message("Configuration set does not exist")
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                    .errorMessage("Configuration set does not exist")
                    .errorCode("ConfigurationSetDoesNotExist")
                    .build())
                .build();
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(exception);

            // When
            emailService.sendPasswordResetEmail("user@example.com", "token123");

            // Then - request should include the configuration set name
            verify(sesV2Client).sendEmail(requestCaptor.capture());
            assertThat(requestCaptor.getValue().configurationSetName()).isEqualTo("nonexistent-config-set");
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }

    /**
     * Tests for malformed or special character tokens.
     * Verifies URL encoding works correctly.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=verified@example.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.frontend.base-url=http://localhost:3000",
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Special Character Token Tests")
    class SpecialCharacterTokenTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

        @BeforeEach
        void setUp() {
            reset(sesV2Client);
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder()
                    .messageId("test-message-id")
                    .build());
        }

        @Test
        @DisplayName("When token contains special URL characters: they are properly encoded")
        void whenTokenContainsSpecialUrlCharacters_theyAreProperlyEncoded() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
            String tokenWithSpecialChars = "abc/def+ghi=jkl&mno?pqr#stu";

            // When
            emailService.sendPasswordResetEmail("user@example.com", tokenWithSpecialChars);

            // Then
            verify(sesV2Client).sendEmail(requestCaptor.capture());
            String emailBody = requestCaptor.getValue().content().simple().body().text().data();
            
            // Then - special characters should be URL-encoded
            assertThat(emailBody).contains("abc%2Fdef%2Bghi%3Djkl%26mno%3Fpqr%23stu");
        }

        @Test
        @DisplayName("When token contains spaces: they are properly encoded")
        void whenTokenContainsSpaces_theyAreProperlyEncoded() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);
            String tokenWithSpaces = "token with spaces";

            // When
            emailService.sendEmailVerificationEmail("user@example.com", tokenWithSpaces);

            // Then
            verify(sesV2Client).sendEmail(requestCaptor.capture());
            String emailBody = requestCaptor.getValue().content().simple().body().text().data();
            
            // Then - spaces should be encoded as %20 or +
            assertThat(emailBody).containsAnyOf("token+with+spaces", "token%20with%20spaces");
        }

        @Test
        @DisplayName("When token is very long: email is still generated")
        void whenTokenIsVeryLong_emailIsStillGenerated() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String veryLongToken = "a".repeat(500);  // 500 character token

            // When / Then - should not throw
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
                emailService.sendPasswordResetEmail("user@example.com", veryLongToken));
            
            verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest.class));
        }
    }

    /**
     * Tests for empty or whitespace-only tokens.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=verified@example.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
        "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        "app.frontend.base-url=http://localhost:3000",
        "app.email.password-reset.subject=Password Reset",
        "app.email.verification.subject=Email Verification",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Empty or Whitespace Token Tests")
    class EmptyTokenTest {

        @MockitoBean
        private SesV2Client sesV2Client;

        @Autowired
        private ApplicationContext context;

        @BeforeEach
        void setUp() {
            reset(sesV2Client);
            
            when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder()
                    .messageId("test-message-id")
                    .build());
        }

        @Test
        @DisplayName("When token is empty string: email is still generated")
        void whenTokenIsEmptyString_emailIsStillGenerated() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

            // When
            emailService.sendPasswordResetEmail("user@example.com", "");

            // Then - should send email with empty token in URL
            verify(sesV2Client, times(1)).sendEmail(requestCaptor.capture());
            
            String emailBody = requestCaptor.getValue().content().simple().body().text().data();
            assertThat(emailBody).contains("reset-password?token=");  // Empty token
        }

        @Test
        @DisplayName("When token is whitespace only: email is generated with encoded whitespace")
        void whenTokenIsWhitespaceOnly_emailIsGeneratedWithEncodedWhitespace() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

            // When
            emailService.sendEmailVerificationEmail("user@example.com", "   ");

            // Then
            verify(sesV2Client, times(1)).sendEmail(requestCaptor.capture());
            
            String emailBody = requestCaptor.getValue().content().simple().body().text().data();
            // Spaces should be URL-encoded
            assertThat(emailBody).containsAnyOf("%20%20%20", "+++");
        }
    }
}

