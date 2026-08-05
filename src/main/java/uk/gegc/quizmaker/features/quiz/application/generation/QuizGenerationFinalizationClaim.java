package uk.gegc.quizmaker.features.quiz.application.generation;

/** Result of attempting to own finalization for one generated quiz. */
public enum QuizGenerationFinalizationClaim {
    CLAIMED,
    ALREADY_FINALIZED,
    IN_PROGRESS,
    TERMINAL;

    public boolean shouldFinalize() {
        return this == CLAIMED;
    }
}
