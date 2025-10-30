package uk.gegc.quizmaker.features.documentProcess.domain;

/**
 * Base exception thrown when link fetching fails.
 */
public class LinkFetchException extends RuntimeException {
    
    public LinkFetchException(String message) {
        super(message);
    }
    
    public LinkFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}

