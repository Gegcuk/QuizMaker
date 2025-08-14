package uk.gegc.quizmaker.shared.email;

public interface EmailService {
    void sendPasswordResetEmail(String email, String resetToken);
    void sendEmailVerificationEmail(String email, String verificationToken);
}
