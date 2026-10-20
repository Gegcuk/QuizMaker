package uk.gegc.quizmaker.features.ai.application;

/** Ends provider work only; already validated output can still be finalized. */
public class GenerationDeadlineExceededException extends RuntimeException {
    public GenerationDeadlineExceededException() {
        super("Generation deadline exceeded");
    }
}
