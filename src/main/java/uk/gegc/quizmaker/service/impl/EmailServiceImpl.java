package uk.gegc.quizmaker.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.service.EmailService;

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

    @Override
    public void sendPasswordResetEmail(String email, String resetToken) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject(passwordResetSubject);
            message.setText(createPasswordResetEmailContent(resetToken));
            
            mailSender.send(message);
            log.info("Password reset email sent successfully to: {}", maskEmail(email));
        } catch (Exception e) {
            log.error("Failed to send password reset email to: {}", maskEmail(email), e);
            // Don't throw the exception to maintain security through obscurity
            // The user won't know if the email was sent or not
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
        
        return String.format("""
            Hello,
            
            You have requested to reset your password for your QuizMaker account.
            
            To reset your password, please click on the following link:
            %s
            
            This link will expire in 1 hour for security reasons.
            
            If you did not request this password reset, please ignore this email.
            Your password will remain unchanged.
            
            Best regards,
            The QuizMaker Team
            """, resetUrl);
    }
}
