package uk.gegc.quizmaker.features.quiz.application.generation;

/** A former worker must stop without failing or changing the current execution. */
public final class GenerationLeaseLostException extends RuntimeException {
    public GenerationLeaseLostException() {
        super("Generation execution no longer owns its lease", null, false, false);
    }
}
