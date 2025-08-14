package uk.gegc.quizmaker.shared.exception;

/**
 * Exception thrown when quiz generation fails
 */
public class QuizGenerationException extends RuntimeException {

    public QuizGenerationException(String message) {
        super(message);
    }

    public QuizGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
} 