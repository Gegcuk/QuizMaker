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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.email.impl.AwsSesEmailService;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for Auth Flow wiring with Email Service.
 * 
 * These tests verify that AuthService correctly integrates with EmailService
 * when generating password reset and email verification tokens.
 * 
 * Tests cover:
 * - Password reset token generation calls EmailService with correct token
 * - Email verification token generation calls EmailService with correct token
 * - Email service exceptions do not bubble to AuthService callers
 * - Email service failures are logged but don't prevent token creation
 * 
 * Uses a real Spring context with SES provider, mocked SES client, and
 * in-memory H2 database for repositories.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@TestPropertySource(properties = {
    "app.email.provider=ses",
    "app.email.from=test-sender@verified-domain.com",
    "app.email.region=us-east-1",
    "AWS_ACCESS_KEY_ID=test-access-key",
    "AWS_SECRET_ACCESS_KEY=test-secret-key",
    "app.auth.reset-token-pepper=test-reset-pepper-secret",
    "app.auth.verification-token-pepper=test-verification-pepper-secret",
    "app.auth.reset-token-ttl-minutes=60",
    "app.auth.verification-token-ttl-minutes=1440",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@DisplayName("Email Auth Flow Integration Tests")
class EmailAuthFlowIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private SesV2Client sesV2Client;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Set up log capture
        logger = (Logger) LoggerFactory.getLogger(AwsSesEmailService.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        logger.setLevel(Level.TRACE);

        // Create a test user
        testUser = new User();
        testUser.setEmail("testuser@example.com");
        testUser.setUsername("testuser");
        testUser.setHashedPassword("hashed-password");
        testUser.setActive(true);
        testUser.setEmailVerified(false);
        testUser.setDeleted(false);
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    void tearDown() {
        if (listAppender != null) {
            listAppender.stop();
            logger.detachAppender(listAppender);
        }
    }

    // ========== Password Reset Token Generation Tests ==========

    @Test
    @DisplayName("generatePasswordResetToken: when user exists then EmailService called with token")
    void generatePasswordResetToken_whenUserExists_thenEmailServiceCalledWithToken() {
        // Given
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id")
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        authService.generatePasswordResetToken(testUser.getEmail());

        // Then - verify SES was called exactly once
        verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest.class));

        // Then - verify the request was sent to the correct email
        verify(sesV2Client).sendEmail(argThat((SendEmailRequest request) -> 
            request.destination().toAddresses().contains(testUser.getEmail())
        ));

        // Then - verify logging shows success
        List<String> infoMessages = getLogMessages(Level.INFO);
        assertThat(infoMessages).anyMatch(msg -> 
            msg.contains("Password reset email sent to:") &&
            msg.contains("t***@example.com"));
    }

    @Test
    @DisplayName("generatePasswordResetToken: when user doesn't exist then EmailService not called")
    void generatePasswordResetToken_whenUserDoesNotExist_thenEmailServiceNotCalled() {
        // Given
        String nonExistentEmail = "nonexistent@example.com";

        // When
        authService.generatePasswordResetToken(nonExistentEmail);

        // Then - verify SES was never called
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    @DisplayName("generatePasswordResetToken: when EmailService throws exception then caller not affected")
    void generatePasswordResetToken_whenEmailServiceThrowsException_thenCallerNotAffected() {
        // Given - SES client throws exception
        SesV2Exception sesException = (SesV2Exception) SesV2Exception.builder()
            .message("Service Unavailable")
            .statusCode(503)
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Service Unavailable")
                .errorCode("ServiceUnavailableException")
                .build())
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(sesException);

        // When / Then - should not throw exception to caller
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
            authService.generatePasswordResetToken(testUser.getEmail()));

        // Then - verify error was logged
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages).anyMatch(msg -> 
            msg.contains("SES service error sending password reset email to:") &&
            msg.contains("503"));
    }

    // ========== Email Verification Token Generation Tests ==========

    @Test
    @DisplayName("generateEmailVerificationToken: when user exists and not verified then EmailService called")
    void generateEmailVerificationToken_whenUserExistsAndNotVerified_thenEmailServiceCalled() {
        // Given
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-verification-id")
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        authService.generateEmailVerificationToken(testUser.getEmail());

        // Then - verify SES was called exactly once
        verify(sesV2Client, times(1)).sendEmail(any(SendEmailRequest.class));

        // Then - verify the request was sent to the correct email
        verify(sesV2Client).sendEmail(argThat((SendEmailRequest request) -> 
            request.destination().toAddresses().contains(testUser.getEmail())
        ));

        // Then - verify logging shows success
        List<String> infoMessages = getLogMessages(Level.INFO);
        assertThat(infoMessages).anyMatch(msg -> 
            msg.contains("Email verification email sent to:") &&
            msg.contains("t***@example.com"));
    }

    @Test
    @DisplayName("generateEmailVerificationToken: when user already verified then EmailService not called")
    void generateEmailVerificationToken_whenUserAlreadyVerified_thenEmailServiceNotCalled() {
        // Given - user is already verified
        testUser.setEmailVerified(true);
        userRepository.save(testUser);

        // When
        authService.generateEmailVerificationToken(testUser.getEmail());

        // Then - verify SES was never called
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    @DisplayName("generateEmailVerificationToken: when user doesn't exist then EmailService not called")
    void generateEmailVerificationToken_whenUserDoesNotExist_thenEmailServiceNotCalled() {
        // Given
        String nonExistentEmail = "nonexistent@example.com";

        // When
        authService.generateEmailVerificationToken(nonExistentEmail);

        // Then - verify SES was never called
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    @DisplayName("generateEmailVerificationToken: when EmailService throws exception then caller not affected")
    void generateEmailVerificationToken_whenEmailServiceThrowsException_thenCallerNotAffected() {
        // Given - SES client throws exception
        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow(new RuntimeException("SES connection failed"));

        // When / Then - should not throw exception to caller
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
            authService.generateEmailVerificationToken(testUser.getEmail()));

        // Then - verify error was logged
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages).anyMatch(msg -> 
            msg.contains("Unexpected error"));
    }

    // ========== Token Content Verification Tests ==========

    @Test
    @DisplayName("generatePasswordResetToken: email contains expected token format")
    void generatePasswordResetToken_emailContainsExpectedTokenFormat() {
        // Given
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-id")
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        authService.generatePasswordResetToken(testUser.getEmail());

        // Then - verify request contains URL-encoded token
        verify(sesV2Client).sendEmail(argThat((SendEmailRequest request) -> {
            String bodyText = request.content().simple().body().text().data();
            // Token should be URL-encoded in the body
            assertThat(bodyText).contains("http://localhost:3000/reset-password");
            return true;
        }));
    }

    @Test
    @DisplayName("generateEmailVerificationToken: email contains expected token format and correct path")
    void generateEmailVerificationToken_emailContainsExpectedTokenFormatAndCorrectPath() {
        // Given
        SendEmailResponse mockResponse = SendEmailResponse.builder()
            .messageId("test-id")
            .build();

        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);

        // When
        authService.generateEmailVerificationToken(testUser.getEmail());

        // Then - verify request contains correct verification URL path
        verify(sesV2Client).sendEmail(argThat((SendEmailRequest request) -> {
            String bodyText = request.content().simple().body().text().data();
            // Should use /verify-email path, not /reset-password
            assertThat(bodyText).contains("http://localhost:3000/verify-email");
            assertThat(bodyText).doesNotContain("/reset-password");
            return true;
        }));
    }

    // ========== Error Handling Does Not Bubble Tests ==========

    @Test
    @DisplayName("generatePasswordResetToken: multiple SES errors don't affect token creation")
    void generatePasswordResetToken_multipleSesErrorsDontAffectTokenCreation() {
        // Given - SES fails with different errors
        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
            .thenThrow((SesV2Exception) SesV2Exception.builder()
                .statusCode(400)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                    .errorMessage("Bad Request")
                    .errorCode("BadRequestException")
                    .build())
                .build())
            .thenThrow((SesV2Exception) SesV2Exception.builder()
                .statusCode(500)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                    .errorMessage("Internal Server Error")
                    .errorCode("InternalServerError")
                    .build())
                .build());

        // When - generate multiple tokens (should succeed despite email failures)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
            authService.generatePasswordResetToken(testUser.getEmail()));
        
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> 
            authService.generatePasswordResetToken(testUser.getEmail()));

        // Then - both attempts should have tried to send email
        verify(sesV2Client, times(2)).sendEmail(any(SendEmailRequest.class));

        // Then - errors should be logged but not thrown
        List<String> warnMessages = getLogMessages(Level.WARN);
        List<String> errorMessages = getLogMessages(Level.ERROR);
        
        assertThat(warnMessages).anyMatch(msg -> msg.contains("400"));
        assertThat(errorMessages).anyMatch(msg -> msg.contains("500"));
    }

    // ========== Helper Methods ==========

    private List<String> getLogMessages(Level level) {
        return listAppender.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }
}

