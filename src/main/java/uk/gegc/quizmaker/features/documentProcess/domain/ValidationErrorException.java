package uk.gegc.quizmaker.features.documentProcess.domain;

/**
 * Exception thrown when validation errors occur in document processing.
 */
public class ValidationErrorException extends RuntimeException {
    
    public ValidationErrorException(String message) {
        super(message);
    }
    
    public ValidationErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
