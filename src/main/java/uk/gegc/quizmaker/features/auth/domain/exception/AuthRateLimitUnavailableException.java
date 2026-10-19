package uk.gegc.quizmaker.features.auth.domain.exception;

public class AuthRateLimitUnavailableException extends RuntimeException {
    public AuthRateLimitUnavailableException(Throwable cause) {
        super("Authentication protection is temporarily unavailable. Please retry later.", cause);
    }
}
