package uk.gegc.quizmaker.shared.exception;

public class ApiError extends RuntimeException {
    public ApiError(String message) {
        super(message);
    }
}