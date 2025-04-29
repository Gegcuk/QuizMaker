package uk.gegc.quizmaker.exception;

public class ApiError extends RuntimeException {
    public ApiError(String message) {
        super(message);
    }
}
