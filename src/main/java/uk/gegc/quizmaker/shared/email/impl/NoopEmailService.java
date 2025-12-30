package uk.gegc.quizmaker.shared.email.impl;

import lombok.extern.slf4j.Slf4j;
import uk.gegc.quizmaker.shared.email.EmailService;

/**
 * No-op email service implementation for local development and testing.
 * 
 * This implementation:
 * - Logs email send attempts but never actually sends emails
 * - Prevents accidental email sends in development
 * - Useful when AWS credentials are not configured locally
 * 
 * Activated when: app.email.provider=noop (default in dev)
 */
@Slf4j
public class NoopEmailService implements EmailService {

    public NoopEmailService() {
        log.info("NoopEmailService initialized - emails will be logged but not sent");
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetToken) {
        log.info("[NOOP] Would send password reset email to: {} with token: {}", 
                maskEmail(email), maskToken(resetToken));
    }

    @Override
    public void sendEmailVerificationEmail(String email, String verificationToken) {
        log.info("[NOOP] Would send email verification to: {} with token: {}", 
                maskEmail(email), maskToken(verificationToken));
    }

    @Override
    public void sendPlainTextEmail(String to, String subject, String body) {
        log.info("[NOOP] Would send plain text email to: {} with subject: {}", maskEmail(to), subject);
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

    private String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return "***";
        }
        if (token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
