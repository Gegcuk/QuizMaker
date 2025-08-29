package uk.gegc.quizmaker.features.documentProcess.domain;

/**
 * Exception thrown when text normalization fails.
 */
public class NormalizationFailedException extends RuntimeException {
    
    public NormalizationFailedException(String message) {
        super(message);
    }
    
    public NormalizationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
