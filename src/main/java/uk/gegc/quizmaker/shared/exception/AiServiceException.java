package uk.gegc.quizmaker.shared.exception;

/**
 * Exception thrown when AI service encounters an error
 */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
} 