package uk.gegc.quizmaker.shared.exception;

public class UnsupportedQuestionTypeException extends RuntimeException {
    public UnsupportedQuestionTypeException(String message) {
        super(message);
    }
}