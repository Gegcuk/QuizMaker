package uk.gegc.quizmaker.features.documentProcess.domain;

/**
 * Exception thrown when fetched content exceeds size limits.
 */
public class ContentSizeLimitException extends LinkFetchException {
    
    public ContentSizeLimitException(String message) {
        super(message);
    }
    
    public ContentSizeLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}

