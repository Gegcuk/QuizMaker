package uk.gegc.quizmaker.exception;

public class UnathorizedException extends RuntimeException {
    public UnathorizedException(String message) {
        super(message);
    }
}
