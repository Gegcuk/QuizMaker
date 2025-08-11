package uk.gegc.quizmaker.exception;

/**
 * Exception thrown when attempting to use a one-time share link that has already been consumed.
 * Maps to HTTP 410 Gone status.
 */
public class ShareLinkAlreadyUsedException extends RuntimeException {
    
    public ShareLinkAlreadyUsedException(String message) {
        super(message);
    }
    
    public ShareLinkAlreadyUsedException(String message, Throwable cause) {
        super(message, cause);
    }
}
