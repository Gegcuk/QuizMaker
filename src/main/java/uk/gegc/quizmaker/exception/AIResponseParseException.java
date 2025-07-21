package uk.gegc.quizmaker.exception;

/**
 * Exception thrown when AI response parsing fails
 */
public class AIResponseParseException extends RuntimeException {

    public AIResponseParseException(String message) {
        super(message);
    }

    public AIResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
} 