package uk.gegc.quizmaker.features.quiz.domain.exception;

public class GenerationOperationInProgressException extends RuntimeException {

    public GenerationOperationInProgressException() {
        super("The matching quiz-generation request is still being initialized. Retry with the same Idempotency-Key shortly.");
    }
}
