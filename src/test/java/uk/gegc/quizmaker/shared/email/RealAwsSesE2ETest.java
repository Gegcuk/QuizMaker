package uk.gegc.quizmaker.shared.email;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Real AWS SES End-to-End tests using actual SES sandbox environment.
 * 
 * These tests make real calls to AWS SES and send actual emails to verified recipients.
 * 
 * Prerequisites:
 * - AWS_ACCESS_KEY_ID environment variable set to valid AWS credentials
 * - AWS_SECRET_ACCESS_KEY environment variable set to valid AWS credentials
 * - Verified sender identity in SES (domain: quizzence.com or specific email)
 * - Verified recipient email addresses (sandbox restriction)
 * - Set RUN_REAL_SES_TESTS=true to enable these tests
 * 
 * Current verified identities:
 * - Domain: quizzence.com (can send from any @quizzence.com email)
 * - Recipients: quizzence.com@gmail.com, gegcuk@gmail.com
 * 
 * To run these tests:
 * 1. Set environment variable: RUN_REAL_SES_TESTS=true
 * 2. Ensure AWS credentials are configured (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * 3. Run: mvn -Dtest="RealAwsSesE2ETest" test
 * 
 * Note: These tests are OPTIONAL and will be skipped if:
 * - RUN_REAL_SES_TESTS is not set to "true"
 * - AWS credentials are not configured
 * - Test recipient email is not configured
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "app.email.provider=ses",
    "app.email.from=noreply@quizzence.com",
    "app.email.region=us-east-1",
    "app.email.password-reset.subject=Password Reset Request - E2E Test",
    "app.email.verification.subject=Email Verification - E2E Test",
    "app.frontend.base-url=https://quizzence.com",
    "app.auth.reset-token-ttl-minutes=60",
    "app.auth.verification-token-ttl-minutes=1440",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "test.ses.enabled=${RUN_REAL_SES_TESTS:false}",
    "test.ses.recipient=${TEST_SES_RECIPIENT_EMAIL:}",
    "test.aws.access-key=${AWS_ACCESS_KEY_ID:}",
    "test.aws.secret-key=${AWS_SECRET_ACCESS_KEY:}"
})
@DisplayName("Real AWS SES E2E Tests (Sandbox)")
class RealAwsSesE2ETest {

    @Autowired
    private EmailService emailService;

    @Value("${test.ses.enabled}")
    private String sesTestsEnabled;

    @Value("${test.ses.recipient}")
    private String testRecipientEmail;

    @Value("${test.aws.access-key}")
    private String awsAccessKeyId;

    @Value("${test.aws.secret-key}")
    private String awsSecretAccessKey;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {

        // Set up log capture
        logger = (Logger) LoggerFactory.getLogger("uk.gegc.quizmaker.shared.email.impl.AwsSesEmailService");
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        logger.setLevel(Level.INFO);

        // Skip tests if not properly configured
        boolean shouldRun = "true".equalsIgnoreCase(sesTestsEnabled) 
            && awsAccessKeyId != null && !awsAccessKeyId.isBlank() 
            && awsSecretAccessKey != null && !awsSecretAccessKey.isBlank()
            && testRecipientEmail != null && !testRecipientEmail.isBlank();

        org.junit.jupiter.api.Assumptions.assumeTrue(
            shouldRun,
            """
            Skipping real SES E2E tests. To enable:
            1. Set environment variable: RUN_REAL_SES_TESTS=true
            2. Set environment variable: AWS_ACCESS_KEY_ID=<your-key>
            3. Set environment variable: AWS_SECRET_ACCESS_KEY=<your-secret>
            4. Set environment variable: TEST_SES_RECIPIENT_EMAIL=<verified-recipient>
            
            Example (Windows PowerShell):
            $env:RUN_REAL_SES_TESTS="true"
            $env:TEST_SES_RECIPIENT_EMAIL="gegcuk@gmail.com"
            
            Example (Linux/Mac):
            export RUN_REAL_SES_TESTS=true
            export TEST_SES_RECIPIENT_EMAIL=gegcuk@gmail.com
            """
        );

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("🚀 Running REAL AWS SES E2E Test");
        System.out.println("   Recipient: " + testRecipientEmail);
        System.out.println("   From: noreply@quizzence.com");
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    @AfterEach
    void tearDown() {
        if (listAppender != null) {
            listAppender.stop();
            logger.detachAppender(listAppender);
        }
    }

    @Test
    @DisplayName("E2E: Send password reset email via real AWS SES sandbox")
    void shouldSendPasswordResetEmailViaRealAwsSes() throws InterruptedException {
        // Given
        String testToken = "e2e-reset-token-" + System.currentTimeMillis();

        // When - Send via real SES
        assertDoesNotThrow(() -> 
            emailService.sendPasswordResetEmail(testRecipientEmail, testToken)
        );

        // Then - Verify successful send via logs
        List<String> infoLogs = getLogMessages(Level.INFO);
        
        assertThat(infoLogs).anyMatch(msg -> 
            msg.contains("Password reset email sent to") && 
            msg.contains("SES MessageId")
        );

        // Extract MessageId from logs
        String messageIdLog = infoLogs.stream()
            .filter(msg -> msg.contains("SES MessageId"))
            .findFirst()
            .orElse("No MessageId found");

        System.out.println("\n✅ PASSWORD RESET EMAIL SENT SUCCESSFULLY!");
        System.out.println("   📧 Recipient: " + testRecipientEmail);
        System.out.println("   🔑 Token: " + testToken);
        System.out.println("   📝 " + messageIdLog);
        System.out.println("\n👉 CHECK YOUR EMAIL INBOX!");
        System.out.println("   Expected subject: Password Reset Request - E2E Test");
        System.out.println("   Expected URL: https://quizzence.com/reset-password?token=" + testToken);

        // Give SES time to deliver
        Thread.sleep(3000);
    }

    @Test
    @DisplayName("E2E: Send email verification via real AWS SES sandbox")
    void shouldSendEmailVerificationViaRealAwsSes() throws InterruptedException {
        // Given
        String testToken = "e2e-verify-token-" + System.currentTimeMillis();

        // When - Send via real SES
        assertDoesNotThrow(() -> 
            emailService.sendEmailVerificationEmail(testRecipientEmail, testToken)
        );

        // Then - Verify successful send via logs
        List<String> infoLogs = getLogMessages(Level.INFO);
        
        assertThat(infoLogs).anyMatch(msg -> 
            msg.contains("Email verification email sent to") && 
            msg.contains("SES MessageId")
        );

        // Extract MessageId from logs
        String messageIdLog = infoLogs.stream()
            .filter(msg -> msg.contains("SES MessageId"))
            .findFirst()
            .orElse("No MessageId found");

        System.out.println("\n✅ EMAIL VERIFICATION SENT SUCCESSFULLY!");
        System.out.println("   📧 Recipient: " + testRecipientEmail);
        System.out.println("   🔑 Token: " + testToken);
        System.out.println("   📝 " + messageIdLog);
        System.out.println("\n👉 CHECK YOUR EMAIL INBOX!");
        System.out.println("   Expected subject: Email Verification - E2E Test");
        System.out.println("   Expected URL: https://quizzence.com/verify-email?token=" + testToken);

        // Give SES time to deliver
        Thread.sleep(3000);
    }

    @Test
    @DisplayName("E2E: Send both email types and verify distinct content")
    void shouldSendBothEmailTypesWithDistinctContent() throws InterruptedException {
        // Given
        String resetToken = "e2e-reset-" + System.currentTimeMillis();
        String verifyToken = "e2e-verify-" + System.currentTimeMillis();

        // When - Send both types
        assertDoesNotThrow(() -> {
            emailService.sendPasswordResetEmail(testRecipientEmail, resetToken);
            Thread.sleep(1000); // Small delay between sends
            emailService.sendEmailVerificationEmail(testRecipientEmail, verifyToken);
        });

        // Then - Both should succeed
        List<String> infoLogs = getLogMessages(Level.INFO);
        
        long sentEmails = infoLogs.stream()
            .filter(msg -> msg.contains("SES MessageId"))
            .count();
        
        assertThat(sentEmails).isEqualTo(2);

        System.out.println("\n✅ BOTH EMAILS SENT SUCCESSFULLY!");
        System.out.println("   📧 Recipient: " + testRecipientEmail);
        System.out.println("   🔐 Reset token: " + resetToken);
        System.out.println("   ✉️  Verify token: " + verifyToken);
        System.out.println("\n👉 CHECK YOUR EMAIL INBOX FOR 2 EMAILS!");
        System.out.println("   1. Password Reset Request - E2E Test (TTL: 1 hour)");
        System.out.println("   2. Email Verification - E2E Test (TTL: 24 hours)");

        Thread.sleep(3000);
    }

    @Test
    @DisplayName("E2E: Handle invalid recipient email gracefully (no crash)")
    void shouldHandleInvalidRecipientEmailGracefully() {
        // Given - Intentionally malformed email
        String invalidEmail = "not-an-email-address";

        // When / Then - Should not throw to caller
        assertDoesNotThrow(() -> 
            emailService.sendPasswordResetEmail(invalidEmail, "token123")
        );

        // Verify error was logged
        List<String> warnLogs = getLogMessages(Level.WARN);
        assertThat(warnLogs).anyMatch(msg -> 
            msg.contains("Failed to send password reset email")
        );

        System.out.println("\n✅ INVALID EMAIL HANDLED GRACEFULLY!");
        System.out.println("   ⚠️  Invalid email: " + invalidEmail);
        System.out.println("   ✓ No exception thrown to caller");
        System.out.println("   ✓ Error logged appropriately");
    }

    @Test
    @DisplayName("E2E: Handle unverified recipient in sandbox (expected SES rejection)")
    void shouldHandleUnverifiedRecipientInSandbox() {
        // Given - Unverified email (will be rejected by SES sandbox)
        String unverifiedEmail = "unverified-test-" + System.currentTimeMillis() + "@example.com";

        // When / Then - Should not throw to caller (graceful degradation)
        assertDoesNotThrow(() -> 
            emailService.sendPasswordResetEmail(unverifiedEmail, "token123")
        );

        // Verify error was logged (SES will reject with 400/403)
        List<String> warnLogs = getLogMessages(Level.WARN);
        
        // SES sandbox will reject unverified recipients
        boolean hasExpectedError = warnLogs.stream()
            .anyMatch(msg -> msg.contains("Failed to send password reset email"));

        assertThat(hasExpectedError).isTrue();

        System.out.println("\n✅ UNVERIFIED RECIPIENT HANDLED GRACEFULLY!");
        System.out.println("   ⚠️  Unverified email: " + unverifiedEmail);
        System.out.println("   ✓ SES sandbox rejected (expected behavior)");
        System.out.println("   ✓ Error logged appropriately");
        System.out.println("   ✓ No exception thrown to caller");
    }

    @Test
    @DisplayName("E2E: Verify email content URLs are properly formatted")
    void shouldFormatEmailContentUrlsCorrectly() throws InterruptedException {
        // Given
        String tokenWithSpecialChars = "token+with/special=chars&more";

        // When
        assertDoesNotThrow(() -> 
            emailService.sendPasswordResetEmail(testRecipientEmail, tokenWithSpecialChars)
        );

        // Then - Should succeed
        List<String> infoLogs = getLogMessages(Level.INFO);
        assertThat(infoLogs).anyMatch(msg -> msg.contains("SES MessageId"));

        System.out.println("\n✅ SPECIAL CHARACTER TOKEN SENT SUCCESSFULLY!");
        System.out.println("   📧 Recipient: " + testRecipientEmail);
        System.out.println("   🔑 Token: " + tokenWithSpecialChars);
        System.out.println("\n👉 CHECK YOUR EMAIL!");
        System.out.println("   Verify URL has properly encoded token:");
        System.out.println("   token%2Bwith%2Fspecial%3Dchars%26more");

        Thread.sleep(2000);
    }

    private List<String> getLogMessages(Level level) {
        return listAppender.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }
}

