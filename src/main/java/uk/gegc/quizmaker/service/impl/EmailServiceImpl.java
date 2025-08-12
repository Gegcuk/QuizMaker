package uk.gegc.quizmaker.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.service.EmailService;
import jakarta.annotation.PostConstruct;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.email.password-reset.subject:Password Reset Request}")
    private String passwordResetSubject;

    @Value("${app.email.password-reset.base-url:http://localhost:3000}")
    private String baseUrl;
    
    @Value("${app.auth.reset-token-ttl-minutes:60}")
    private long resetTokenTtlMinutes;

    @Value("${app.email.verification.subject:Email Verification - QuizMaker}")
    private String verificationSubject;

    @Value("${app.auth.verification-token-ttl-minutes:1440}")
    private long verificationTokenTtlMinutes;

    @PostConstruct
    void verifyEmailConfiguration() {
        if (fromEmail == null || fromEmail.isBlank()) {
            throw new IllegalStateException("spring.mail.username is not configured");
        }
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetToken) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject(passwordResetSubject);
            message.setText(createPasswordResetEmailContent(resetToken));
            
            mailSender.send(message);
            log.info("Password reset email sent to: {}", maskEmail(email));
        } catch (Exception e) {
            log.error("Failed to send password reset email to: {}", maskEmail(email), e);
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }

    @Override
    public void sendEmailVerificationEmail(String email, String verificationToken) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject(verificationSubject);
            message.setText(createEmailVerificationContent(verificationToken));
            
            mailSender.send(message);
            log.info("Email verification email sent to: {}", maskEmail(email));
        } catch (Exception e) {
            log.error("Failed to send email verification email to: {}", maskEmail(email), e);
            throw new RuntimeException("Failed to send email verification email", e);
        }
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

        return String.format("""
            Hello,

            You have requested to reset your password for your QuizMaker account.

            To reset your password, please click on the following link:
            %s

            This link will expire in %s for security reasons.

            If you did not request this password reset, please ignore this email.
            Your password will remain unchanged.

            Best regards,
            The QuizMaker Team
            """, resetUrl, timeDescription);
    }

    private String createEmailVerificationContent(String verificationToken) {
        String encodedToken = URLEncoder.encode(verificationToken, StandardCharsets.UTF_8);
        String verificationUrl = baseUrl + "/verify-email?token=" + encodedToken;
        
        String timeDescription = formatTimeDescription(verificationTokenTtlMinutes);

        return String.format("""
            Hello,

            Thank you for registering with QuizMaker!

            To complete your registration and verify your email address, please click on the following link:
            %s

            This link will expire in %s for security reasons.

            If you did not create a QuizMaker account, please ignore this email.

            Best regards,
            The QuizMaker Team
            """, verificationUrl, timeDescription);
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
}
