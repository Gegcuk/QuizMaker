package uk.gegc.quizmaker.features.billing.domain.exception;

/**
 * Exception thrown when a webhook request contains a payload that is too large
 * and poses a security risk. This should return a 500 status code to indicate
 * a server-side security concern.
 */
public class LargePayloadSecurityException extends RuntimeException {
    
    public LargePayloadSecurityException(String message) {
        super(message);
    }
    
    public LargePayloadSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
