package uk.gegc.quizmaker.shared.email;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gegc.quizmaker.shared.email.impl.EmailServiceImpl;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;



/**
 * SMTP Provider Sanity Tests for EmailServiceImpl.
 * 
 * These tests verify that:
 * - SMTP email service skips sending when spring.mail.username is not configured (logs warning)
 * - SMTP email service successfully sends emails when properly configured (using mocked JavaMailSender)
 * - Email content and subjects match the SMTP implementation
 * - Email masking works correctly in logs
 * 
 * Based on requirements from email-tests.md:
 * "Skip When Unconfigured: With app.email.provider=smtp and empty spring.mail.username, 
 * sending logs a skip warning and returns."
 * "Happy Path: Verify that an email is sent and subject/body align to the SMTP implementation."
 */
@DisplayName("SMTP Email Service Sanity Tests")
class SmtpEmailServiceTest {

    /**
     * Tests for unconfigured SMTP provider (no spring.mail.username).
     * Verifies that the service logs warnings and skips sending when not configured.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=smtp",
        "spring.mail.username=",  // Empty - not configured
        "spring.mail.host=localhost",
        "spring.mail.port=3025",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Skip When Unconfigured Tests")
    class SkipWhenUnconfiguredTest {

        @Autowired
        private ApplicationContext context;

        private ListAppender<ILoggingEvent> listAppender;
        private Logger logger;

        @BeforeEach
        void setUp() {
            // Set up log capture
            logger = (Logger) LoggerFactory.getLogger(EmailServiceImpl.class);
            listAppender = new ListAppender<>();
            listAppender.start();
            logger.addAppender(listAppender);
            logger.setLevel(Level.TRACE);
        }

        @AfterEach
        void tearDown() {
            if (listAppender != null) {
                listAppender.stop();
                logger.detachAppender(listAppender);
            }
        }

        @Test
        @DisplayName("When spring.mail.username is empty: EmailService bean should be EmailServiceImpl")
        void whenUsernameEmpty_emailServiceBeanShouldBeEmailServiceImpl() {
            // Given - context loaded with empty username
            EmailService emailService = context.getBean(EmailService.class);

            // Then - EmailService should be EmailServiceImpl even when unconfigured
            assertThat(emailService).isNotNull();
            assertThat(emailService).isInstanceOf(EmailServiceImpl.class);
        }

        @Test
        @DisplayName("When spring.mail.username is empty: sendPasswordResetEmail should log skip warning")
        void whenUsernameEmpty_sendPasswordResetEmailShouldLogSkipWarning() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String email = "user@example.com";
            String token = "reset-token-123";

            // Clear previous logs
            listAppender.list.clear();

            // When
            emailService.sendPasswordResetEmail(email, token);

            // Then - should log warning that email is being skipped
            List<String> warnMessages = getLogMessages(Level.WARN);
            assertThat(warnMessages).anyMatch(msg -> 
                msg.contains("Email service disabled") && 
                msg.contains("skipping password reset email") &&
                msg.contains("u***@example.com")); // Masked email
        }

        @Test
        @DisplayName("When spring.mail.username is empty: sendEmailVerificationEmail should log skip warning")
        void whenUsernameEmpty_sendEmailVerificationEmailShouldLogSkipWarning() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String email = "newuser@example.com";
            String token = "verify-token-456";

            // Clear previous logs
            listAppender.list.clear();

            // When
            emailService.sendEmailVerificationEmail(email, token);

            // Then - should log warning that email is being skipped
            List<String> warnMessages = getLogMessages(Level.WARN);
            assertThat(warnMessages).anyMatch(msg -> 
                msg.contains("Email service disabled") && 
                msg.contains("skipping email verification") &&
                msg.contains("n***@example.com")); // Masked email
        }

        @Test
        @DisplayName("When spring.mail.username is empty: should not throw exceptions")
        void whenUsernameEmpty_shouldNotThrowExceptions() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String email = "test@example.com";
            String token = "test-token";

            // When / Then - should not throw
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
                emailService.sendPasswordResetEmail(email, token);
                emailService.sendEmailVerificationEmail(email, token);
            });
        }


        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }

    /**
     * Tests for configured SMTP provider using mocked JavaMailSender.
     * Verifies that emails are sent with correct content and subjects.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=smtp",
        "spring.mail.host=smtp.example.com",
        "spring.mail.port=587",
        "spring.mail.username=test-sender@example.com",
        "spring.mail.password=test-password",
        "app.email.password-reset.subject=Password Reset Request",
        "app.email.verification.subject=Email Verification - QuizMaker",
        "app.frontend.base-url=http://localhost:3000",
        "app.auth.reset-token-ttl-minutes=60",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Happy Path with Configured SMTP Tests")
    class HappyPathWithConfiguredSmtpTest {

        @MockitoBean
        private JavaMailSender mailSender;

        @Autowired
        private ApplicationContext context;

        private ListAppender<ILoggingEvent> listAppender;
        private Logger logger;

        @BeforeEach
        void setUp() {
            // Set up log capture
            logger = (Logger) LoggerFactory.getLogger(EmailServiceImpl.class);
            listAppender = new ListAppender<>();
            listAppender.start();
            logger.addAppender(listAppender);
            logger.setLevel(Level.TRACE);

            // Reset mock between tests
            reset(mailSender);
        }

        @AfterEach
        void tearDown() {
            if (listAppender != null) {
                listAppender.stop();
                logger.detachAppender(listAppender);
            }
        }

        @Test
        @DisplayName("sendPasswordResetEmail: should send email via JavaMailSender")
        void sendPasswordResetEmail_shouldSendEmailViaJavaMailSender() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String recipientEmail = "recipient@example.com";
            String resetToken = "reset-token-abc123";

            // When
            emailService.sendPasswordResetEmail(recipientEmail, resetToken);

            // Then - JavaMailSender.send should be called
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender, times(1)).send(messageCaptor.capture());

            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getTo()).containsExactly(recipientEmail);
            assertThat(sentMessage.getFrom()).isEqualTo("test-sender@example.com");
            assertThat(sentMessage.getSubject()).isEqualTo("Password Reset Request");
            assertThat(sentMessage.getText()).contains("reset your password");
            assertThat(sentMessage.getText()).contains("http://localhost:3000/reset-password?token=");
            assertThat(sentMessage.getText()).contains("expire in 1 hour");
        }

        @Test
        @DisplayName("sendPasswordResetEmail: should log success message")
        void sendPasswordResetEmail_shouldLogSuccessMessage() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String recipientEmail = "user@example.com";
            String resetToken = "reset-token-xyz789";

            // Clear previous logs
            listAppender.list.clear();

            // When
            emailService.sendPasswordResetEmail(recipientEmail, resetToken);

            // Then - should log success with masked email
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("Password reset email sent to") &&
                msg.contains("u***@example.com")); // Masked email
        }

        @Test
        @DisplayName("sendEmailVerificationEmail: should send email via JavaMailSender")
        void sendEmailVerificationEmail_shouldSendEmailViaJavaMailSender() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String recipientEmail = "newuser@example.com";
            String verificationToken = "verify-token-def456";

            // When
            emailService.sendEmailVerificationEmail(recipientEmail, verificationToken);

            // Then - JavaMailSender.send should be called
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender, times(1)).send(messageCaptor.capture());

            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getTo()).containsExactly(recipientEmail);
            assertThat(sentMessage.getFrom()).isEqualTo("test-sender@example.com");
            assertThat(sentMessage.getSubject()).isEqualTo("Email Verification - QuizMaker");
            assertThat(sentMessage.getText()).contains("verify your email address");
            assertThat(sentMessage.getText()).contains("http://localhost:3000/verify-email?token=");
            assertThat(sentMessage.getText()).contains("expire in 24 hours"); // 1440 minutes = 24 hours
        }

        @Test
        @DisplayName("sendEmailVerificationEmail: should log success message")
        void sendEmailVerificationEmail_shouldLogSuccessMessage() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String recipientEmail = "alice@example.com";
            String verificationToken = "verify-token-ghi789";

            // Clear previous logs
            listAppender.list.clear();

            // When
            emailService.sendEmailVerificationEmail(recipientEmail, verificationToken);

            // Then - should log success with masked email
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("Email verification email sent to") &&
                msg.contains("a***@example.com")); // Masked email
        }

        @Test
        @DisplayName("Password reset email: should URL-encode special characters in token")
        void passwordResetEmail_shouldUrlEncodeSpecialCharactersInToken() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String recipientEmail = "user@example.com";
            String resetToken = "token-with-special/chars+test";

            // When
            emailService.sendPasswordResetEmail(recipientEmail, resetToken);

            // Then
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender).send(messageCaptor.capture());

            String content = messageCaptor.getValue().getText();
            assertThat(content).contains("token-with-special%2Fchars%2Btest");
        }

        @Test
        @DisplayName("Verification email: should URL-encode special characters in token")
        void verificationEmail_shouldUrlEncodeSpecialCharactersInToken() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);
            String recipientEmail = "user@example.com";
            String verificationToken = "token-with-special/chars+test";

            // When
            emailService.sendEmailVerificationEmail(recipientEmail, verificationToken);

            // Then
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mailSender).send(messageCaptor.capture());

            String content = messageCaptor.getValue().getText();
            assertThat(content).contains("token-with-special%2Fchars%2Btest");
        }

        @Test
        @DisplayName("Email content: should format TTL correctly for different durations")
        void emailContent_shouldFormatTtlCorrectlyForDifferentDurations() {
            // This test verifies the formatTimeDescription logic indirectly
            // Reset TTL is 60 minutes = "1 hour"
            // Verification TTL is 1440 minutes = "24 hours"
            
            EmailService emailService = context.getBean(EmailService.class);
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
            
            // Password reset (60 minutes)
            emailService.sendPasswordResetEmail("user1@example.com", "token1");
            verify(mailSender, times(1)).send(messageCaptor.capture());
            assertThat(messageCaptor.getValue().getText()).contains("expire in 1 hour");
            
            reset(mailSender);
            
            // Email verification (1440 minutes = 24 hours)
            emailService.sendEmailVerificationEmail("user2@example.com", "token2");
            verify(mailSender, times(1)).send(messageCaptor.capture());
            assertThat(messageCaptor.getValue().getText()).contains("expire in 24 hours");
        }

        @Test
        @DisplayName("Multiple emails: should send all successfully")
        void multipleEmails_shouldSendAllSuccessfully() {
            // Given
            EmailService emailService = context.getBean(EmailService.class);

            // When - send multiple emails
            emailService.sendPasswordResetEmail("user1@example.com", "token1");
            emailService.sendPasswordResetEmail("user2@example.com", "token2");
            emailService.sendEmailVerificationEmail("user3@example.com", "token3");

            // Then - all should be sent
            verify(mailSender, times(3)).send(any(SimpleMailMessage.class));
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }
}

