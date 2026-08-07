package uk.gegc.quizmaker.features.auth.domain.exception;

public class AuthSessionStoreUnavailableException extends RuntimeException {

    public AuthSessionStoreUnavailableException(Throwable cause) {
        super("Authentication session state is temporarily unavailable. Please retry.", cause);
    }
}
