package uk.gegc.quizmaker.features.repetition.application.exception;

public class RepetitionAlreadyProcessedException extends RuntimeException {
    public RepetitionAlreadyProcessedException(String message) {
        super(message);
    }
}
