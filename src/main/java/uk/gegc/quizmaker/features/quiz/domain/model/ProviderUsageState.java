package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Completeness of provider-reported usage for a generation job.
 */
public enum ProviderUsageState {
    NOT_RECORDED,
    COMPLETE,
    INCOMPLETE,
    LEGACY_REVIEW
}
