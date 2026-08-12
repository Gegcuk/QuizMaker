package uk.gegc.quizmaker.features.quiz.application.generation;

public class QuizGenerationCheckpointException extends RuntimeException {

    public QuizGenerationCheckpointException(String message) {
        super(message);
    }

    public QuizGenerationCheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
