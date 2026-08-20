package uk.gegc.quizmaker.features.auth.domain.exception;

public class OAuthExchangeRequestException extends RuntimeException {
    public OAuthExchangeRequestException() {
        super("OAuth exchange request is not valid");
    }
}
