package uk.gegc.quizmaker.service;

public interface EmailService {
    void sendPasswordResetEmail(String email, String resetToken);
    void sendEmailVerificationEmail(String email, String verificationToken);
}
