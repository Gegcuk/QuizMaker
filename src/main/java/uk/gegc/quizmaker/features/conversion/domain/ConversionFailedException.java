package uk.gegc.quizmaker.features.conversion.domain;

/**
 * Exception thrown when document conversion fails due to processing errors.
 */
public class ConversionFailedException extends RuntimeException {
    
    public ConversionFailedException(String message) {
        super(message);
    }
    
    public ConversionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
