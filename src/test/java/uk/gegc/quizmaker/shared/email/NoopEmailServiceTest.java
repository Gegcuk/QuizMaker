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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import uk.gegc.quizmaker.shared.email.impl.NoopEmailService;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for NoopEmailService behavior.
 * 
 * These tests verify that:
 * - NoopEmailService is activated when app.email.provider=noop
 * - Email methods log [NOOP] messages with masked email and token
 * - No SES client bean is present (no actual email sending)
 * - Email masking works correctly for security
 * - Token masking works correctly for security
 * 
 * Based on requirements from email-tests.md:
 * "With app.email.provider=noop, calling sendPasswordResetEmail and sendEmailVerificationEmail 
 * logs [NOOP] with masked email and token; no SES client interaction occurs."
 */
@DisplayName("Noop Email Service Tests")
class NoopEmailServiceTest {

    /**
     * Spring context tests to verify that NoopEmailService is properly configured
     * when app.email.provider=noop.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=noop",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Spring Context Configuration Tests")
    class NoopProviderContextTest {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("When provider=noop: EmailService bean should be NoopEmailService")
        void whenNoopProvider_emailServiceShouldBeNoopEmailService() {
            // Given / When
            EmailService emailService = context.getBean(EmailService.class);

            // Then
            assertThat(emailService).isNotNull();
            assertThat(emailService).isInstanceOf(NoopEmailService.class);
        }

        @Test
        @DisplayName("When provider=noop: SesV2Client bean should NOT exist")
        void whenNoopProvider_sesV2ClientBeanShouldNotExist() {
            // Given / When / Then - SesV2Client should not be in context
            assertThatThrownBy(() -> context.getBean(SesV2Client.class))
                .hasMessageContaining("No qualifying bean of type");
        }

        @Test
        @DisplayName("When provider=noop: NoopEmailService should be the primary EmailService")
        void whenNoopProvider_noopEmailServiceShouldBePrimary() {
            // Given / When
            EmailService primaryEmailService = context.getBean(EmailService.class);
            NoopEmailService noopEmailService = context.getBean(NoopEmailService.class);

            // Then - primary bean should be the same instance as the typed bean
            assertThat(primaryEmailService).isSameAs(noopEmailService);
        }
    }

    /**
     * Tests for password reset email behavior with NoopEmailService.
     * Verifies logging of [NOOP] messages with masked email and token.
     */
    @Nested
    @DisplayName("Password Reset Email Behavior Tests")
    class PasswordResetEmailBehaviorTest {

        private NoopEmailService noopEmailService;
        private ListAppender<ILoggingEvent> listAppender;
        private Logger logger;

        @BeforeEach
        void setUp() {
            noopEmailService = new NoopEmailService();
            
            // Set up log capture
            logger = (Logger) LoggerFactory.getLogger(NoopEmailService.class);
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
        @DisplayName("sendPasswordResetEmail: should log [NOOP] message with masked email")
        void sendPasswordResetEmail_shouldLogNoopMessageWithMaskedEmail() {
            // Given
            String email = "john.doe@example.com";
            String token = "reset-token-123456789";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should log [NOOP] message
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("password reset email") &&
                msg.contains("j***@example.com")); // Masked email
        }

        @Test
        @DisplayName("sendPasswordResetEmail: should log with masked token")
        void sendPasswordResetEmail_shouldLogWithMaskedToken() {
            // Given
            String email = "user@example.com";
            String token = "reset-token-123456789";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should log masked token (first 4 + ... + last 4)
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("rese...6789")); // Masked token
        }

        @Test
        @DisplayName("sendPasswordResetEmail: should not throw exceptions")
        void sendPasswordResetEmail_shouldNotThrowExceptions() {
            // Given
            String email = "test@example.com";
            String token = "test-token-123";

            // When / Then - should not throw
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
                noopEmailService.sendPasswordResetEmail(email, token));
        }

        @Test
        @DisplayName("sendPasswordResetEmail: should handle empty email gracefully")
        void sendPasswordResetEmail_shouldHandleEmptyEmailGracefully() {
            // Given
            String email = "";
            String token = "test-token-123";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should log with masked placeholder
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("***")); // Masked empty email
        }

        @Test
        @DisplayName("sendPasswordResetEmail: should handle short token gracefully")
        void sendPasswordResetEmail_shouldHandleShortTokenGracefully() {
            // Given
            String email = "user@example.com";
            String token = "short";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should log with masked placeholder for short token
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("***")); // Short tokens are fully masked
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }

    /**
     * Tests for email verification behavior with NoopEmailService.
     * Verifies logging of [NOOP] messages with masked email and token.
     */
    @Nested
    @DisplayName("Email Verification Behavior Tests")
    class EmailVerificationBehaviorTest {

        private NoopEmailService noopEmailService;
        private ListAppender<ILoggingEvent> listAppender;
        private Logger logger;

        @BeforeEach
        void setUp() {
            noopEmailService = new NoopEmailService();
            
            // Set up log capture
            logger = (Logger) LoggerFactory.getLogger(NoopEmailService.class);
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
        @DisplayName("sendEmailVerificationEmail: should log [NOOP] message with masked email")
        void sendEmailVerificationEmail_shouldLogNoopMessageWithMaskedEmail() {
            // Given
            String email = "alice.smith@example.com";
            String token = "verify-token-987654321";

            // When
            noopEmailService.sendEmailVerificationEmail(email, token);

            // Then - should log [NOOP] message
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("email verification") &&
                msg.contains("a***@example.com")); // Masked email
        }

        @Test
        @DisplayName("sendEmailVerificationEmail: should log with masked token")
        void sendEmailVerificationEmail_shouldLogWithMaskedToken() {
            // Given
            String email = "user@example.com";
            String token = "verify-token-987654321";

            // When
            noopEmailService.sendEmailVerificationEmail(email, token);

            // Then - should log masked token (first 4 + ... + last 4)
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("veri...4321")); // Masked token
        }

        @Test
        @DisplayName("sendEmailVerificationEmail: should not throw exceptions")
        void sendEmailVerificationEmail_shouldNotThrowExceptions() {
            // Given
            String email = "test@example.com";
            String token = "test-token-123";

            // When / Then - should not throw
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
                noopEmailService.sendEmailVerificationEmail(email, token));
        }

        @Test
        @DisplayName("sendEmailVerificationEmail: should handle null email gracefully")
        void sendEmailVerificationEmail_shouldHandleNullEmailGracefully() {
            // Given
            String email = null;
            String token = "test-token-123";

            // When
            noopEmailService.sendEmailVerificationEmail(email, token);

            // Then - should log with masked placeholder
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("***")); // Masked null email
        }

        @Test
        @DisplayName("sendEmailVerificationEmail: should handle null token gracefully")
        void sendEmailVerificationEmail_shouldHandleNullTokenGracefully() {
            // Given
            String email = "user@example.com";
            String token = null;

            // When
            noopEmailService.sendEmailVerificationEmail(email, token);

            // Then - should log with masked placeholder for null token
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("[NOOP]") && 
                msg.contains("***")); // Null tokens are masked as ***
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }

    /**
     * Tests for email masking edge cases.
     * Ensures PII is properly protected in logs.
     */
    @Nested
    @DisplayName("Email Masking Edge Cases")
    class EmailMaskingEdgeCasesTest {

        private NoopEmailService noopEmailService;
        private ListAppender<ILoggingEvent> listAppender;
        private Logger logger;

        @BeforeEach
        void setUp() {
            noopEmailService = new NoopEmailService();
            
            // Set up log capture
            logger = (Logger) LoggerFactory.getLogger(NoopEmailService.class);
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
        @DisplayName("Email masking: should mask single-character local part")
        void emailMasking_shouldMaskSingleCharacterLocalPart() {
            // Given
            String email = "a@example.com";
            String token = "test-token-123456789";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should mask single-character email
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("***@example.com")); // Single char becomes ***
        }

        @Test
        @DisplayName("Email masking: should mask two-character local part")
        void emailMasking_shouldMaskTwoCharacterLocalPart() {
            // Given
            String email = "ab@example.com";
            String token = "test-token-123456789";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should show first char + ***
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("a***@example.com")); // First char + ***
        }

        @Test
        @DisplayName("Email masking: should handle email without @ symbol")
        void emailMasking_shouldHandleEmailWithoutAtSymbol() {
            // Given
            String email = "notanemail";
            String token = "test-token-123456789";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should mask as ***
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("***@***")); // No @ found
        }

        @Test
        @DisplayName("Email masking: should preserve domain while masking local part")
        void emailMasking_shouldPreserveDomainWhileMaskingLocalPart() {
            // Given
            String email = "verylonglocalpart@subdomain.example.com";
            String token = "test-token-123456789";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should show first char + *** + full domain
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("v***@subdomain.example.com")); // First char + *** + domain
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }

    /**
     * Tests for token masking edge cases.
     * Ensures sensitive tokens are properly protected in logs.
     */
    @Nested
    @DisplayName("Token Masking Edge Cases")
    class TokenMaskingEdgeCasesTest {

        private NoopEmailService noopEmailService;
        private ListAppender<ILoggingEvent> listAppender;
        private Logger logger;

        @BeforeEach
        void setUp() {
            noopEmailService = new NoopEmailService();
            
            // Set up log capture
            logger = (Logger) LoggerFactory.getLogger(NoopEmailService.class);
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
        @DisplayName("Token masking: should fully mask tokens <= 8 characters")
        void tokenMasking_shouldFullyMaskShortTokens() {
            // Given
            String email = "user@example.com";
            String token = "12345678"; // Exactly 8 chars

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should fully mask short token
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("***") && 
                !msg.contains("1234")); // Should not show any part of token
        }

        @Test
        @DisplayName("Token masking: should show first 4 and last 4 for long tokens")
        void tokenMasking_shouldShowFirstAndLastFourForLongTokens() {
            // Given
            String email = "user@example.com";
            String token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"; // 26 chars

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should show ABCD...WXYZ
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("ABCD...WXYZ"));
        }

        @Test
        @DisplayName("Token masking: should handle 9-character token (minimum for partial reveal)")
        void tokenMasking_shouldHandleNineCharacterToken() {
            // Given
            String email = "user@example.com";
            String token = "123456789"; // 9 chars

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should show 1234...6789
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("1234...6789"));
        }

        @Test
        @DisplayName("Token masking: should handle empty token")
        void tokenMasking_shouldHandleEmptyToken() {
            // Given
            String email = "user@example.com";
            String token = "";

            // When
            noopEmailService.sendPasswordResetEmail(email, token);

            // Then - should mask as ***
            List<String> infoMessages = getLogMessages(Level.INFO);
            assertThat(infoMessages).anyMatch(msg -> 
                msg.contains("***"));
        }

        private List<String> getLogMessages(Level level) {
            return listAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
        }
    }
}

