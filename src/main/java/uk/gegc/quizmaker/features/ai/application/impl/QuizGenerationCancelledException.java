package uk.gegc.quizmaker.features.ai.application.impl;

/**
 * Internal control-flow signal used to stop job-backed generation after cancellation wins.
 */
final class QuizGenerationCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    QuizGenerationCancelledException() {
        super("Quiz generation cancelled", null, false, false);
    }
}
