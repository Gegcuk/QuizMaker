package uk.gegc.quizmaker.features.conversion.domain;

/**
 * Exception thrown when a document format is not supported by any converter.
 */
public class UnsupportedFormatException extends RuntimeException {
    
    public UnsupportedFormatException(String message) {
        super(message);
    }
    
    public UnsupportedFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
