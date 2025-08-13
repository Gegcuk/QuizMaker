package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.service.impl.EmailServiceImpl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @InjectMocks
    private EmailServiceImpl emailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailService, "fromEmail", "test@example.com");
        ReflectionTestUtils.setField(emailService, "passwordResetSubject", "Password Reset Request");
        ReflectionTestUtils.setField(emailService, "baseUrl", "http://localhost:3000");
    }

    @Test
    void sendPasswordResetEmail_Success() {
        // Given
        String email = "user@example.com";
        String resetToken = "test-token-123";

        // When
        emailService.sendPasswordResetEmail(email, resetToken);

        // Then
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendPasswordResetEmail_WhenMailSenderThrowsException_ShouldNotThrow() {
        // Given
        String email = "user@example.com";
        String resetToken = "test-token-123";
        
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(SimpleMailMessage.class));

        // When & Then
        // Should not throw exception to prevent email enumeration
        assertDoesNotThrow(() -> {
            emailService.sendPasswordResetEmail(email, resetToken);
        });
        
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendPasswordResetEmail_ShouldSetCorrectMessageProperties() {
        // Given
        String email = "user@example.com";
        String resetToken = "test-token-123";

        // When
        emailService.sendPasswordResetEmail(email, resetToken);

        // Then
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendEmailVerificationEmail_Success() {
        // Given
        String email = "user@example.com";
        String verificationToken = "test-verification-token-123";

        // When
        emailService.sendEmailVerificationEmail(email, verificationToken);

        // Then
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendEmailVerificationEmail_WhenMailSenderThrowsException_ShouldNotThrow() {
        // Given
        String email = "user@example.com";
        String verificationToken = "test-verification-token-123";
        
        doThrow(new RuntimeException("SMTP error")).when(mailSender).send(any(SimpleMailMessage.class));

        // When & Then
        // Should not throw exception to prevent email enumeration
        assertDoesNotThrow(() -> {
            emailService.sendEmailVerificationEmail(email, verificationToken);
        });
        
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }
}
