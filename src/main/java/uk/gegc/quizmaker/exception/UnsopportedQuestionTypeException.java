package uk.gegc.quizmaker.exception;

public class UnsopportedQuestionTypeException extends RuntimeException {
    public UnsopportedQuestionTypeException(String message) {
        super(message);
    }
}
