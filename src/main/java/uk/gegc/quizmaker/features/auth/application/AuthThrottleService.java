package uk.gegc.quizmaker.features.auth.application;

/** Shared authentication admission; a denial must precede credential/session side effects. */
public interface AuthThrottleService {
    void login(String identifier, String network);
    void registration(String email, String username, String network);
    void providerRegistration(String provider, String subject, String email);
    void refresh(String refreshToken, String network);
    void existing(String operation, String key);
}
