package uk.gegc.quizmaker.features.auth.domain.exception;

public class OAuthExchangeStoreUnavailableException extends RuntimeException {
    public OAuthExchangeStoreUnavailableException(Throwable cause) {
        super("OAuth exchange is temporarily unavailable. Please restart sign-in.", cause);
    }
}
