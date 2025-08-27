package uk.gegc.quizmaker.features.conversion.domain;

/**
 * Exception thrown when document conversion fails.
 */
public class ConversionException extends Exception {
    
    public ConversionException(String message) {
        super(message);
    }
    
    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
