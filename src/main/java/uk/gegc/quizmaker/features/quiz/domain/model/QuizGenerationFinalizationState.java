package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Internal entitlement state for generated quizzes.
 *
 * <p>This is intentionally separate from {@link GenerationStatus}. A job is
 * {@code COMPLETED} only after this state has reached {@code SUCCEEDED}; the
 * extra state makes interrupted finalization recoverable without changing the
 * public generation-status contract.</p>
 */
public enum QuizGenerationFinalizationState {
    NOT_STARTED,
    FINALIZING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REVIEW_REQUIRED,
    LEGACY
}
